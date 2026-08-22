package paulscode.mupen64plusae.rollback;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android service for rollback netplay with RMG-K lobby compatibility.
 *
 * Flow:
 * 1. Connect to lobby server via WebSocket
 * 2. Create/join a room
 * 3. Host starts the match → server sends MATCH_BEGIN to all players
 * 4. NAT punch-through via UDP anchor
 * 5. Start GekkoNet rollback session
 * 6. Run rollback execution loop
 */
public class RollbackNetplayService extends Service {

    private static final String TAG = "RollbackNetplay";

    private final IBinder binder = new LocalBinder();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<NetplayListener> listeners = new CopyOnWriteArrayList<>();

    private RmgkLobbyClient lobbyClient;
    private String playerName = "Android Player";
    private int localDelay = 2;
    private int predictionWindow = 7;

    // Full ROM info, set by RollbackNetplayActivity right after binding.
    // Needed to actually launch GameActivity (load the ROM, start the
    // core) once a match is found - see RollbackGameBridge.
    private String romPath = "";
    private String zipPath = "";
    private String romMd5 = "";
    private String romCrc = "";
    private String romHeaderName = "";
    private byte romCountryCode = 0;
    private String romArtPath = "";
    private String romGoodName = "";
    private String romDisplayName = "";

    public void setRomInfo(String romPath, String zipPath, String romMd5, String romCrc,
                            String romHeaderName, byte romCountryCode, String romArtPath,
                            String romGoodName, String romDisplayName) {
        this.romPath = romPath != null ? romPath : "";
        this.zipPath = zipPath != null ? zipPath : "";
        this.romMd5 = romMd5 != null ? romMd5 : "";
        this.romCrc = romCrc != null ? romCrc : "";
        this.romHeaderName = romHeaderName != null ? romHeaderName : "";
        this.romCountryCode = romCountryCode;
        this.romArtPath = romArtPath != null ? romArtPath : "";
        this.romGoodName = romGoodName != null ? romGoodName : "";
        this.romDisplayName = romDisplayName != null ? romDisplayName : "";
        RollbackDebugLog.log(this, "RollbackNetplayService",
            "setRomInfo() called: romPath='" + this.romPath + "' romMd5='" + this.romMd5 + "'");
    }

    /**
     * Launches the real GameActivity (loads the ROM, starts the core) in
     * rollback mode, and blocks the calling thread (must be a background
     * thread) until the core reports ready or the timeout elapses.
     *
     * @return null on success, or a human-readable failure reason.
     */
    private String startGameForRollback() {
        RollbackDebugLog.log(this, "RollbackNetplayService",
            "startGameForRollback() ENTER: romPath='" + romPath + "' romMd5='" + romMd5
            + "' zipPath='" + zipPath + "' romGoodName='" + romGoodName + "'");

        if (romPath.isEmpty() || romMd5.isEmpty()) {
            RollbackDebugLog.log(this, "RollbackNetplayService",
                "ABORT: No ROM selected (romPath or romMd5 empty)");
            return "No ROM selected";
        }

        Object waitHandle = RollbackGameBridge.beginWaitForCoreReady(this);

        Intent intent = new Intent();
        intent.setClassName(this, "paulscode.android.mupen64plusae.game.GameActivity");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(RollbackGameLaunchKeys.ROM_PATH, romPath);
        intent.putExtra(RollbackGameLaunchKeys.ZIP_PATH, zipPath);
        intent.putExtra(RollbackGameLaunchKeys.ROM_MD5, romMd5);
        intent.putExtra(RollbackGameLaunchKeys.ROM_CRC, romCrc);
        intent.putExtra(RollbackGameLaunchKeys.ROM_HEADER_NAME, romHeaderName);
        intent.putExtra(RollbackGameLaunchKeys.ROM_COUNTRY_CODE, romCountryCode);
        intent.putExtra(RollbackGameLaunchKeys.ROM_ART_PATH, romArtPath);
        intent.putExtra(RollbackGameLaunchKeys.ROM_GOOD_NAME, romGoodName);
        intent.putExtra(RollbackGameLaunchKeys.ROM_DISPLAY_NAME, romDisplayName);
        intent.putExtra(RollbackGameLaunchKeys.ROLLBACK_MODE, true);

        RollbackDebugLog.log(this, "RollbackNetplayService", "Calling startActivity(GameActivity)");
        try {
            startActivity(intent);
        } catch (Exception e) {
            RollbackDebugLog.error(this, "RollbackNetplayService", "startActivity() threw", e);
            return "startActivity() threw: " + e.getMessage();
        }
        RollbackDebugLog.log(this, "RollbackNetplayService",
            "startActivity() returned normally, now waiting for core ready...");

        // Cold start (ROM load + core init, in a *separate process* -
        // GameActivity runs in :EmulationProcess) can genuinely take a
        // while on slower devices - 30s gives real headroom without
        // hanging forever if something is actually wrong.
        String result = RollbackGameBridge.awaitCoreReady(waitHandle, 30_000);
        RollbackDebugLog.log(this, "RollbackNetplayService",
            "awaitCoreReady() returned: " + (result == null ? "SUCCESS" : result));
        return result;
    }

    public class LocalBinder extends Binder {
        public RollbackNetplayService getService() {
            return RollbackNetplayService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        RollbackCrashLogger.install(this);
        super.onCreate();
        lobbyClient = new RmgkLobbyClient();
        lobbyClient.addListener(lobbyListener);
        Log.i(TAG, "RollbackNetplayService created");
    }

    @Override
    public void onDestroy() {
        stop();
        lobbyClient.disconnectFromServer();
        executor.shutdown();
        super.onDestroy();
    }

    /**
     * Listener for netplay events.
     */
    public interface NetplayListener {
        default void onConnected(long userId) {}
        default void onDisconnected(String reason) {}
        default void onRoomListChanged(List<RmgkLobbyClient.RoomSummary> rooms) {}
        default void onRoomCreated(long roomId) {}
        default void onRoomJoined(long roomId) {}
        default void onRoomJoinFailed(String reason) {}
        default void onRoomLeft(String reason) {}
        default void onRoomStateChanged(JSONObject data) {}
        default void onMatchStarting(long matchId, List<RmgkLobbyClient.MatchPeer> peers) {}
        default void onMatchStarted() {}
        default void onMatchPeerLeft(long matchId, long userId, int slot, String reason) {}
        default void onMatchFinished() {}
        default void onError(String error) {}
        default void onStatusChanged(String status) {}
        default void onPingMeasured(long userId, int rttMs) {}
        default void onChatMessage(String channel, long fromUserId, String fromUsername, String message) {}
        default void onPresenceUpdated(Map<Long, RmgkLobbyClient.LobbyUser> users) {}
    }

    public void addListener(NetplayListener listener) {
        listeners.add(listener);
    }

    public void removeListener(NetplayListener listener) {
        listeners.remove(listener);
    }

    public void setPlayerName(String name) {
        this.playerName = name;
    }

    public void setLocalDelay(int delay) {
        this.localDelay = delay;
    }

    public void setPredictionWindow(int window) {
        this.predictionWindow = window;
    }

    /**
     * Connect to an RMG-K lobby server.
     */
    public void connectToLobby(String wsUrl, List<String> romHashes) {
        notifyStatus("Connecting to lobby...");
        lobbyClient.connectToServer(wsUrl, playerName, romHashes);
    }

    /**
     * Disconnect from the lobby.
     */
    public void disconnectFromLobby() {
        lobbyClient.disconnectFromServer();
    }

    /**
     * Create a room on the lobby.
     */
    public void createRoom(String name, String romName, String romMd5,
                           int maxPlayers, int delay, int prediction, String password) {
        lobbyClient.createRoom(name, romName, romMd5, maxPlayers, delay, prediction, password);
    }

    /**
     * Join an existing room.
     */
    public void joinRoom(long roomId, String password) {
        lobbyClient.joinRoom(roomId, password);
    }

    /**
     * Leave the current room.
     */
    public void leaveRoom() {
        lobbyClient.leaveRoom();
    }

    /**
     * Start the match (host only).
     */
    public void startMatch() {
        lobbyClient.startRoom();
    }

    /**
     * Quick match - auto-match with another player.
     */
    public void quickMatch(String romName, String romMd5) {
        lobbyClient.quickMatchJoin(romName, romMd5);
    }

    /**
     * Cancel quick match.
     */
    public void cancelQuickMatch() {
        lobbyClient.quickMatchCancel();
    }

    /**
     * Send a chat message.
     */
    public void sendChat(String channel, String message) {
        lobbyClient.sendChat(channel, message);
    }

    /**
     * Request a ping measurement to a user.
     */
    public void requestPing(long userId) {
        lobbyClient.requestPingProbe(userId);
    }

    /**
     * Start a direct P2P session (bypass lobby).
     */
    public void startDirectP2P(String gameName, int localPlayer, int localPort,
                               String remoteIp, int remotePort, int localDelay) {
        Log.i(TAG, "Starting direct P2P: " + remoteIp + ":" + remotePort);
        notifyStatus("Starting game...");

        executor.execute(() -> {
            try {
                String gameStartFailure = startGameForRollback();
                if (gameStartFailure != null) {
                    notifyError("Failed to start game: " + gameStartFailure);
                    return;
                }

                notifyStatus("Connecting P2P...");

                boolean success = RollbackNative.nativeStartP2PSession(
                    gameName, 2, 4, localPlayer, localPort,
                    remoteIp, remotePort, localDelay, 7);

                if (!success) {
                    String reason = RollbackNative.nativeGetLastError();
                    RollbackDebugLog.log(this, "RollbackNetplayService",
                        "nativeStartP2PSession() returned false, reason: " + reason);
                    notifyError("Failed to start P2P session: " + reason);
                    return;
                }

                notifyMatchStarted();

                // Run rollback execution (blocks until session ends)
                RollbackNative.nativeExecute();
                Log.i(TAG, "P2P execution ended");
                notifyMatchFinished();
            } catch (Exception e) {
                Log.e(TAG, "P2P failed", e);
                notifyError("P2P failed: " + e.getMessage());
            }
        });
    }

    /**
     * Stop the current session.
     */
    public void stop() {
        RollbackNative.nativeRequestStop();
        RollbackNative.nativeCloseSession();
        notifyStatus("Stopped");
    }

    /**
     * Check if a game session is active.
     */
    public boolean isSessionActive() {
        return RollbackNative.nativeIsSessionActive();
    }

    /**
     * Get network stats for a player.
     */
    public float[] getNetworkStats(int player) {
        return RollbackNative.nativeGetNetworkStats(player);
    }

    /**
     * Get the lobby client for direct access.
     */
    public RmgkLobbyClient getLobbyClient() {
        return lobbyClient;
    }

    // --- Lobby event handler ---

    private final RmgkLobbyClient.LobbyListener lobbyListener = new RmgkLobbyClient.LobbyListener() {
        @Override
        public void onStateChanged(RmgkLobbyClient.ConnectionState state) {
            switch (state) {
                case CONNECTED:
                    notifyStatus("Connected to lobby");
                    break;
                case CONNECTING:
                    notifyStatus("Connecting...");
                    break;
                case DISCONNECTED:
                    notifyStatus("Disconnected");
                    break;
                case FAILED:
                    notifyError("Connection failed");
                    break;
            }
        }

        @Override
        public void onConnectError(String error) {
            notifyError(error);
        }

        @Override
        public void onHelloOk(long userId, String observedIp, String region) {
            Log.i(TAG, "Lobby: logged in as user " + userId + " region=" + region);
            for (NetplayListener l : listeners) l.onConnected(userId);
        }

        @Override
        public void onHelloFailed(String reason) {
            notifyError("Login failed: " + reason);
        }

        @Override
        public void onPresenceFull(Map<Long, RmgkLobbyClient.LobbyUser> users) {
            for (NetplayListener l : listeners) l.onPresenceUpdated(users);
        }

        @Override
        public void onRoomListChanged(List<RmgkLobbyClient.RoomSummary> rooms) {
            for (NetplayListener l : listeners) l.onRoomListChanged(rooms);
        }

        @Override
        public void onRoomCreated(long roomId) {
            Log.i(TAG, "Room created: " + roomId);
            for (NetplayListener l : listeners) l.onRoomCreated(roomId);
        }

        @Override
        public void onRoomJoinOk(long roomId) {
            Log.i(TAG, "Joined room: " + roomId);
            for (NetplayListener l : listeners) l.onRoomJoined(roomId);
        }

        @Override
        public void onRoomJoinFailed(String reason) {
            for (NetplayListener l : listeners) l.onRoomJoinFailed(reason);
        }

        @Override
        public void onRoomLeft(String reason) {
            for (NetplayListener l : listeners) l.onRoomLeft(reason);
        }

        @Override
        public void onRoomStateChanged(JSONObject data) {
            for (NetplayListener l : listeners) l.onRoomStateChanged(data);
        }

        @Override
        public void onMatchBegin(long matchId, List<RmgkLobbyClient.MatchPeer> peers) {
            Log.i(TAG, "MATCH_BEGIN: match=" + matchId + " peers=" + peers.size());
            handleMatchBegin(matchId, peers);
        }

        @Override
        public void onMatchPeerLeft(long matchId, long userId, int slot, String reason) {
            Log.i(TAG, "Match peer left: user=" + userId + " slot=" + slot + " reason=" + reason);
            // Disconnect the player from GekkoNet
            RollbackNative.nativeDisconnectPlayer(slot);
            for (NetplayListener l : listeners) l.onMatchPeerLeft(matchId, userId, slot, reason);
        }

        @Override
        public void onPingMeasured(long userId, int rttMs) {
            for (NetplayListener l : listeners) l.onPingMeasured(userId, rttMs);
        }

        @Override
        public void onChatMessage(String channel, long fromUserId, String fromUsername, String message) {
            for (NetplayListener l : listeners) l.onChatMessage(channel, fromUserId, fromUsername, message);
        }

        @Override
        public void onQuickMatchStatus(boolean searching, int queueSize) {
            notifyStatus(searching ? "Searching... (queue: " + queueSize + ")" : "Search cancelled");
        }
    };

    /**
     * Handle MATCH_BEGIN from the lobby server.
     * This is where the actual GekkoNet rollback session starts.
     */
    /**
     * Handle MATCH_BEGIN from the lobby server.
     * This is where the actual GekkoNet rollback session starts.
     *
     * The rendering pipeline:
     * 1. The AE opens the ROM and sets up the GL context (via normal game launch)
     * 2. We start the GekkoNet rollback session
     * 3. CoreRollbackExecute runs the emulation loop
     * 4. The video plugin renders to the existing GL context
     * 5. Audio plugin handles audio output
     * 6. The result is the same as normal emulation, but with rollback
     */
    private void handleMatchBegin(long matchId, List<RmgkLobbyClient.MatchPeer> peers) {
        for (NetplayListener l : listeners) l.onMatchStarting(matchId, peers);

        executor.execute(() -> {
            try {
                // Find local peer
                RmgkLobbyClient.MatchPeer localPeer = null;
                for (RmgkLobbyClient.MatchPeer p : peers) {
                    if (p.userId == lobbyClient.getSelfUserId()) {
                        localPeer = p;
                        break;
                    }
                }

                if (localPeer == null) {
                    notifyError("Match start failed: local peer not found");
                    return;
                }

                // Build remote peer lists
                List<Integer> remoteSlots = new ArrayList<>();
                List<String> remoteIps = new ArrayList<>();
                List<Integer> remotePorts = new ArrayList<>();

                for (RmgkLobbyClient.MatchPeer p : peers) {
                    if (p.userId == lobbyClient.getSelfUserId()) continue;

                    // Use local IP if same network
                    String ip = p.publicIp;
                    if (localPeer.publicIp != null && !localPeer.publicIp.isEmpty() &&
                        localPeer.publicIp.equals(p.publicIp) &&
                        p.localIp != null && !p.localIp.isEmpty()) {
                        ip = p.localIp;
                    }

                    remoteSlots.add(p.slot);
                    remoteIps.add(ip);
                    remotePorts.add(p.publicPort);

                    Log.i(TAG, "Remote peer: slot=" + p.slot + " ip=" + ip + ":" + p.publicPort +
                          " name=" + p.username);
                }

                if (remoteSlots.isEmpty()) {
                    notifyError("Match start failed: no remote peers");
                    return;
                }

                // Use lobby client's UDP port for GekkoNet
                int localPort = lobbyClient.getLocalUdpPort();
                if (localPort == 0) localPort = 4444;

                notifyStatus("Starting game...");

                // Load the ROM and start the real emulation core (paused,
                // ready for rollback to drive it frame-by-frame) before
                // trying to configure rollback mode - previously this
                // step was entirely missing, so setupRollbackMode() below
                // always failed because no core was running yet.
                String gameStartFailure = startGameForRollback();
                if (gameStartFailure != null) {
                    notifyError("Failed to start game: " + gameStartFailure);
                    return;
                }

                notifyStatus("Starting rollback session...");

                String gameName = "N64 Game";

                // Start GekkoNet lobby session via JNI. This also
                // configures deterministic mode and input players
                // internally (see coreRollbackSetDeterministic/
                // coreRollbackSetInputPlayers in rollback_jni.cpp) - no
                // separate setup step needed.

                // Start GekkoNet lobby session via JNI
                boolean success = RollbackNative.nativeStartLobbySession(
                    gameName,
                    peers.size(),
                    4,                      // inputSize = sizeof(uint32_t)
                    localPeer.slot,
                    localPort,
                    toIntArray(remoteSlots),
                    remoteIps.toArray(new String[0]),
                    toIntArray(remotePorts),
                    localDelay,             // from settings
                    predictionWindow        // from settings
                );

                if (!success) {
                    String reason = RollbackNative.nativeGetLastError();
                    RollbackDebugLog.log(this, "RollbackNetplayService",
                        "nativeStartLobbySession() returned false, reason: " + reason);
                    notifyError("Failed to start GekkoNet session: " + reason);
                    return;
                }

                notifyMatchStarted();

                // Run rollback execution via JNI
                // This calls CoreDoCommand(M64CMD_ROLLBACK_EXECUTE) which:
                // - Runs the emulation loop with rollback support
                // - The video plugin renders to the existing GL context
                // - Audio plugin handles audio output
                // - GekkoNet handles input sync and state save/load
                boolean execResult = RollbackNative.nativeExecute();

                Log.i(TAG, "Rollback execution ended: " + execResult);
                notifyMatchFinished();

            } catch (Exception e) {
                Log.e(TAG, "Match start failed", e);
                notifyError("Match failed: " + e.getMessage());
            }
        });
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private void notifyStatus(String status) {
        for (NetplayListener l : listeners) l.onStatusChanged(status);
    }

    private void notifyError(String error) {
        RollbackDebugLog.log(this, "RollbackNetplayService", "notifyError: " + error);
        if (RollbackGameBridge.isRollbackSessionActive()) {
            RollbackGameBridge.notifyMatchEnded(this);
        }
        for (NetplayListener l : listeners) l.onError(error);
    }

    private void notifyMatchStarted() {
        // Start overlay as foreground service (Android 8+)
        try {
            Intent overlayIntent = new Intent(this, NetplayOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(overlayIntent);
            } else {
                startService(overlayIntent);
            }
        } catch (Exception e) { Log.e(TAG, "Failed to start overlay", e); }

        for (NetplayListener l : listeners) l.onMatchStarted();
    }

    private void notifyMatchFinished() {
        // Stop overlay
        try {
            Intent overlayIntent = new Intent(this, NetplayOverlayService.class);
            stopService(overlayIntent);
        } catch (Exception e) { /* ignore */ }

        RollbackGameBridge.notifyMatchEnded(this);

        for (NetplayListener l : listeners) l.onMatchFinished();
    }
}
