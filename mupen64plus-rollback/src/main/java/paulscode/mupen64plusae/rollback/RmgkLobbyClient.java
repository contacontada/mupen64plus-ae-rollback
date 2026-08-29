package paulscode.mupen64plusae.rollback;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * RMG-K compatible lobby client for Android.
 * Uses OkHttp WebSocket for the lobby control channel
 * and raw UDP sockets for the anchor/NAT traversal layer.
 */
public class RmgkLobbyClient {

    private static final String TAG = "RmgkLobbyClient";

    // Anchor protocol constants (must match RMG-K)
    private static final byte[] ANCHOR_MAGIC = {'R', 'M', 'G', 'K'};
    private static final byte ANCHOR_OP_REGISTER    = 0x01;
    private static final byte ANCHOR_OP_KEEPALIVE   = 0x02;
    private static final byte ANCHOR_OP_PUNCH       = 0x03;
    private static final byte ANCHOR_OP_PROBE       = 0x04;
    private static final byte ANCHOR_OP_PROBE_REPLY = 0x05;

    private static final int ANCHOR_PUNCH_BURST = 10;
    private static final int PROBE_BURST = 10;
    private static final int PROBE_PACKET_SIZE = 4 + 1 + 8 + 8;
    private static final int HEARTBEAT_INTERVAL_MS = 15_000;
    private static final int HTTP_KEEPALIVE_INTERVAL_MS = 7 * 60 * 1000; // 7 minutes, as requested
    private static final int UDP_KEEPALIVE_INTERVAL_MS = 20_000;
    private static final int PROBE_RETRY_INTERVAL_MS = 300;
    private static final int PROBE_MAX_ATTEMPTS = 4;
    private static final long LEARNED_ROUTE_TTL_MS = 60_000;

    public enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, FAILED
    }

    // --- Data classes ---

    public static class LobbyUser {
        public long id;
        public String username = "";
        public String state = "";
        public String region = "";
        public String country = "";
        public String clientVersion = "";
        public String connection = "";
        public int pingToServer;
        public long currentRoomId;
        public String currentRoomName = "";
    }

    public static class RoomSummary {
        public long id;
        public String name = "";
        public long hostId;
        public String hostName = "";
        public String romName = "";
        public String romMd5 = "";
        public int players;
        public int maxPlayers;
        public String state = "";
        public boolean hasPassword;
        public List<String> playerNames = new ArrayList<>();
    }

    public static class MatchPeer {
        public long userId;
        public String username = "";
        public String publicIp = "";
        public int publicPort;
        public String localIp = "";
        public int slot;
    }

    // --- Listener ---

    public interface LobbyListener {
        default void onStateChanged(ConnectionState state) {}
        default void onConnectError(String error) {}
        default void onHelloOk(long userId, String observedIp, String region) {}
        default void onHelloFailed(String reason) {}
        default void onPresenceFull(Map<Long, LobbyUser> users) {}
        default void onUserAdded(long userId) {}
        default void onUserRemoved(long userId) {}
        default void onRoomListChanged(List<RoomSummary> rooms) {}
        default void onRoomCreated(long roomId) {}
        default void onRoomCreateFailed(String reason) {}
        default void onRoomJoinOk(long roomId) {}
        default void onRoomJoinFailed(String reason) {}
        default void onRoomLeft(String reason) {}
        default void onRoomStateChanged(JSONObject data) {}
        default void onMatchBegin(long matchId, List<MatchPeer> peers) {}
        default void onMatchPeerLeft(long matchId, long userId, int slot, String reason) {}
        default void onPingMeasured(long userId, int rttMs) {}
        default void onPingProbeFailed(long userId) {}
        default void onChatMessage(String channel, long fromUserId, String fromUsername, String message) {}
        default void onQuickMatchStatus(boolean searching, int queueSize) {}
    }

    // --- Internal state ---

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final CopyOnWriteArrayList<LobbyListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Long, LobbyUser> users = new ConcurrentHashMap<>();
    private final Map<Long, Integer> measuredPing = new ConcurrentHashMap<>();
    private final Map<Long, LearnedRoute> learnedRoutes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ProbeInFlight> pendingProbes = new ConcurrentHashMap<>();
    private final Random random = new Random();

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private long selfUserId;
    private String observedIp = "";
    private String region = "";
    private String udpAnchorHost = "";
    private int udpAnchorPort = 6364;
    private int anchorLocalPort;

    // OkHttp WebSocket
    private OkHttpClient httpClient;
    private WebSocket webSocket;

    // UDP
    private DatagramSocket udpSocket;
    private Thread udpReceiveThread;
    private volatile boolean udpRunning;

    // Timers
    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledFuture<?> keepaliveFuture;
    private ScheduledFuture<?> httpKeepaliveFuture;
    private String pendingHttpBaseUrl = "";

    // Pending username/roms for HELLO
    private String pendingUsername = "";
    private List<String> pendingRomHashes = new ArrayList<>();

    private static class ProbeInFlight {
        long targetUserId;
        long sendMs;
        long attemptSendMs;
        String endpoint;
        int attempt;
        long nextAttemptMs;
    }

    private static class LearnedRoute {
        String endpoint;
        long lastSeenMs;
    }

    // --- Public API ---

    public void addListener(LobbyListener listener) { listeners.add(listener); }
    public void removeListener(LobbyListener listener) { listeners.remove(listener); }
    public ConnectionState getState() { return state; }
    public long getSelfUserId() { return selfUserId; }
    public int getLocalUdpPort() { return anchorLocalPort; }
    public Map<Long, LobbyUser> getUsers() { return users; }

    public int getMeasuredPing(long userId) {
        Integer p = measuredPing.get(userId);
        return p != null ? p : -1;
    }

    /**
     * Connect to the RMG-K lobby server.
     */
    public void connectToServer(String wsUrl, String username, List<String> romHashes) {
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) return;

        pendingUsername = username;
        pendingRomHashes = romHashes != null ? romHashes : new ArrayList<>();

        setState(ConnectionState.CONNECTING);

        // Parse anchor host from WS URL
        try {
            // ws://host:port/path or wss://host:port/path
            String hostPart = wsUrl.replace("ws://", "").replace("wss://", "");
            int slashIdx = hostPart.indexOf('/');
            if (slashIdx > 0) hostPart = hostPart.substring(0, slashIdx);
            int colonIdx = hostPart.lastIndexOf(':');
            udpAnchorHost = colonIdx > 0 ? hostPart.substring(0, colonIdx) : hostPart;
        } catch (Exception e) {
            Log.w(TAG, "Could not parse host from URL, using localhost");
            udpAnchorHost = "localhost";
        }

        httpClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for WS
            .build();

        // Free-tier hosts (e.g. Render) spin the service down after a period
        // of inactivity and take 30-60s to "cold start" back up on the next
        // request. Hitting the plain HTTP health-check endpoint first (which
        // wakes the service) avoids the WebSocket handshake itself timing
        // out against a server that hasn't finished waking up yet.
        pingServerAwake(wsUrl, () -> openWebSocket(wsUrl));
    }

    private void openWebSocket(String wsUrl) {
        Request request = new Request.Builder().url(wsUrl).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.i(TAG, "WebSocket connected");
                onWsConnected();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                onWsTextMessage(text);
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                // Binary messages not used by lobby protocol
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
                onWsDisconnected(reason);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WebSocket failure", t);
                String error = t.getMessage();
                if (response != null) error = response.code() + ": " + error;
                onWsError(error);
            }
        });
    }

    /**
     * Best-effort "wake up" ping for free-tier hosts that spin down when
     * idle. Tries a plain HTTP GET against the server's health-check
     * endpoint with a generous timeout and a couple of retries (a cold
     * start can take 30-60s on Render's free tier); proceeds to open the
     * WebSocket regardless of whether the ping succeeded, since the ping
     * is purely an optimization - the WS connection attempt itself is
     * still the source of truth for whether the server is reachable.
     */
    private void pingServerAwake(String wsUrl, Runnable onDone) {
        String httpUrl = wsUrl.replaceFirst("^ws://", "http://").replaceFirst("^wss://", "https://");
        // Strip any path - the health check is served at "/".
        int schemeEnd = httpUrl.indexOf("://") + 3;
        int pathStart = httpUrl.indexOf('/', schemeEnd);
        if (pathStart > 0) httpUrl = httpUrl.substring(0, pathStart);

        final String finalHttpUrl = httpUrl;
        pendingHttpBaseUrl = httpUrl;
        executor.execute(() -> {
            OkHttpClient wakeClient = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();

            boolean awake = false;
            for (int attempt = 1; attempt <= 3 && !awake; attempt++) {
                try {
                    Request req = new Request.Builder().url(finalHttpUrl).get().build();
                    try (Response resp = wakeClient.newCall(req).execute()) {
                        if (resp.isSuccessful()) {
                            awake = true;
                            Log.i(TAG, "Lobby server is awake (attempt " + attempt + ")");
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Wake ping attempt " + attempt + " failed: " + e.getMessage());
                }
            }
            if (!awake) {
                Log.w(TAG, "Could not confirm server is awake after retries; connecting anyway");
            }
            wakeClient.dispatcher().executorService().shutdown();
            mainHandler.post(onDone);
        });
    }

    /**
     * Periodic HTTP keepalive, independent of the WebSocket-level
     * HEARTBEAT, so the underlying host doesn't consider the service idle
     * during a long play session (e.g. Render's free tier spins services
     * down after ~15 minutes with no HTTP requests - a 7-minute period
     * keeps well under that with margin).
     */
    private void sendHttpKeepalive(String httpUrl) {
        executor.execute(() -> {
            try {
                Request req = new Request.Builder().url(httpUrl).get().build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    Log.d(TAG, "HTTP keepalive ping: " + resp.code());
                }
            } catch (Exception e) {
                Log.w(TAG, "HTTP keepalive ping failed: " + e.getMessage());
            }
        });
    }

    /**
     * Disconnect from the lobby server.
     */
    public void disconnectFromServer() {
        stopTimers();
        closeUdpSocket();
        if (webSocket != null) {
            webSocket.close(1000, "client disconnect");
            webSocket = null;
        }
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient = null;
        }
        setState(ConnectionState.DISCONNECTED);
    }

    // --- Room API ---

    public void createRoom(String name, String romName, String romMd5, int maxPlayers,
                           int delay, int prediction, String password) {
        try {
            JSONObject rom = new JSONObject();
            rom.put("name", romName);
            rom.put("md5", romMd5);
            rom.put("region", "");
            JSONObject d = new JSONObject();
            d.put("name", name);
            d.put("rom", rom);
            d.put("maxPlayers", maxPlayers);
            d.put("delay", delay);
            d.put("prediction", prediction);
            // RMG-K's own client always sends pacing=1 ("Smooth") - the
            // picker for other values exists in their UI but is hidden/
            // unused. Matching this exactly since the real server may
            // require the field.
            d.put("pacing", 1);
            if (password != null && !password.isEmpty()) d.put("password", password);
            sendEnvelope("ROOM_CREATE", d);
        } catch (JSONException e) { Log.e(TAG, "createRoom JSON error", e); }
    }

    public void joinRoom(long roomId, String password) {
        try {
            JSONObject d = new JSONObject();
            d.put("roomId", roomId);
            if (password != null && !password.isEmpty()) d.put("password", password);
            sendEnvelope("ROOM_JOIN", d);
        } catch (JSONException e) { Log.e(TAG, "joinRoom JSON error", e); }
    }

    public void leaveRoom() { sendEnvelope("ROOM_LEAVE"); }
    public void startRoom() { sendEnvelope("ROOM_START"); }

    public void quickMatchJoin(String romName, String romMd5) {
        try {
            JSONObject rom = new JSONObject();
            rom.put("name", romName);
            rom.put("md5", romMd5);
            rom.put("region", "");
            JSONObject d = new JSONObject();
            d.put("rom", rom);
            sendEnvelope("QUICK_MATCH_JOIN", d);
        } catch (JSONException e) { Log.e(TAG, "quickMatch JSON error", e); }
    }

    public void quickMatchCancel() { sendEnvelope("QUICK_MATCH_CANCEL"); }

    public void sendChat(String channel, String message) {
        try {
            JSONObject d = new JSONObject();
            d.put("channel", channel);
            d.put("message", message);
            sendEnvelope("CHAT_SEND", d);
        } catch (JSONException e) { Log.e(TAG, "sendChat JSON error", e); }
    }

    public void requestPingProbe(long targetUserId) {
        try {
            JSONObject d = new JSONObject();
            d.put("targetUserId", targetUserId);
            sendEnvelope("PING_PROBE_REQUEST", d);
        } catch (JSONException e) { Log.e(TAG, "requestPingProbe JSON error", e); }
    }

    // --- WebSocket handlers ---

    private void onWsConnected() {
        setState(ConnectionState.CONNECTED);

        // Send HELLO
        try {
            JSONObject data = new JSONObject();
            data.put("username", pendingUsername);
            // The lobby server now gates login on a minimum client version
            // (added in RMG-K 0.9.13 - see RollbackLobbyDialog::onHelloFailed
            // upstream, "requires RMG-K 9.13 or vdev-2367 or newer"). The
            // desktop client reports its actual build version here
            // (CoreGetVersion(), a git tag like "0.9.13"). We had a made-up
            // "1.0.0-android" scheme that has no relationship to RMG-K's
            // versioning, so the server always rejected it as too old.
            // Report the RMG-K release this port is tracking instead, since
            // that's what the server's version gate actually understands.
            data.put("clientVersion", "0.9.13");
            JSONArray romArr = new JSONArray();
            for (String h : pendingRomHashes) romArr.put(h);
            data.put("romHashes", romArr);

            // Detect local IP
            String localIp = detectLocalIPv4();
            if (localIp != null && !localIp.isEmpty()) data.put("localIp", localIp);

            sendEnvelope("HELLO", data, "hello-1");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build HELLO", e);
        }

        // Init UDP anchor
        initUdpAnchor();

        // Start heartbeat
        heartbeatFuture = scheduler.scheduleAtFixedRate(
            this::sendHeartbeat, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Start HTTP keepalive (separate from the WS heartbeat above - see
        // sendHttpKeepalive() for why this exists independently)
        if (!pendingHttpBaseUrl.isEmpty()) {
            httpKeepaliveFuture = scheduler.scheduleAtFixedRate(
                () -> sendHttpKeepalive(pendingHttpBaseUrl),
                HTTP_KEEPALIVE_INTERVAL_MS, HTTP_KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void onWsTextMessage(String text) {
        try {
            JSONObject env = new JSONObject(text);
            String type = env.getString("type");
            JSONObject data = env.optJSONObject("data");

            switch (type) {
                case "HELLO_OK":             handleHelloOk(data); break;
                case "HELLO_FAIL":           handleHelloFail(data); break;
                case "HEARTBEAT_ACK":        break; // ignored
                case "PRESENCE_FULL":        handlePresenceFull(data); break;
                case "PRESENCE_DELTA":       handlePresenceDelta(data); break;
                case "ROOM_LIST":            handleRoomList(data); break;
                case "ROOM_CREATED":         handleRoomCreated(data); break;
                case "ROOM_CREATE_FAIL":     handleRoomCreateFail(data); break;
                case "ROOM_STATE":           handleRoomState(data); break;
                case "ROOM_JOIN_OK":         handleRoomJoinOk(data); break;
                case "ROOM_JOIN_FAIL":       handleRoomJoinFail(data); break;
                case "ROOM_LEFT":            handleRoomLeft(data); break;
                case "CHAT_MSG":             handleChatMsg(data); break;
                case "PING_PROBE_REPLY":     handlePingProbeReply(data); break;
                case "PING_PROBE_INCOMING":  handlePingProbeIncoming(data); break;
                case "MATCH_BEGIN":          handleMatchBegin(data); break;
                case "MATCH_PEER_LEFT":      handleMatchPeerLeft(data); break;
                case "QUICK_MATCH_STATUS":   handleQuickMatchStatus(data); break;
                default: Log.d(TAG, "Unknown msg type: " + type);
            }
        } catch (JSONException e) {
            Log.e(TAG, "JSON parse error", e);
        }
    }

    private void onWsDisconnected(String reason) {
        stopTimers();
        if (state != ConnectionState.FAILED) setState(ConnectionState.DISCONNECTED);
    }

    private void onWsError(String error) {
        notifyConnectError(error != null ? error : "Unknown error");
        setState(ConnectionState.FAILED);
    }

    // --- Envelope send ---

    private void sendEnvelope(String type, JSONObject data, String id) {
        if (webSocket == null) return;
        try {
            JSONObject env = new JSONObject();
            env.put("type", type);
            if (id != null) env.put("id", id);
            if (data != null) env.put("data", data);
            String payload = env.toString();
            webSocket.send(payload);
        } catch (JSONException e) {
            Log.e(TAG, "sendEnvelope JSON error", e);
        }
    }

    private void sendEnvelope(String type, JSONObject data) { sendEnvelope(type, data, null); }
    private void sendEnvelope(String type) { sendEnvelope(type, null, null); }

    // --- Message handlers ---

    private void handleHelloOk(JSONObject d) throws JSONException {
        selfUserId = d.getLong("userId");
        observedIp = d.optString("observedIp", "");
        region = d.optString("region", "");

        String udpAnchor = d.optString("udpAnchor", "");
        if (!udpAnchor.isEmpty() && !udpAnchor.equals("TODO:6364")) {
            int sep = udpAnchor.lastIndexOf(':');
            if (sep > 0) {
                udpAnchorHost = udpAnchor.substring(0, sep);
                udpAnchorPort = Integer.parseInt(udpAnchor.substring(sep + 1));
            }
        }

        notifyHelloOk(selfUserId, observedIp, region);
    }

    private void handleHelloFail(JSONObject d) throws JSONException {
        notifyHelloFailed(d.getString("reason"));
        setState(ConnectionState.FAILED);
    }

    private void handlePresenceFull(JSONObject d) throws JSONException {
        users.clear();
        JSONArray arr = d.getJSONArray("users");
        for (int i = 0; i < arr.length(); i++) {
            LobbyUser u = parseUser(arr.getJSONObject(i));
            users.put(u.id, u);
        }
        notifyPresenceFull();
    }

    private void handlePresenceDelta(JSONObject d) throws JSONException {
        JSONArray added = d.optJSONArray("added");
        if (added != null) {
            for (int i = 0; i < added.length(); i++) {
                LobbyUser u = parseUser(added.getJSONObject(i));
                users.put(u.id, u);
                notifyUserAdded(u.id);
            }
        }
        JSONArray removed = d.optJSONArray("removed");
        if (removed != null) {
            for (int i = 0; i < removed.length(); i++) {
                long id = removed.getLong(i);
                users.remove(id);
                notifyUserRemoved(id);
            }
        }
    }

    private void handleRoomList(JSONObject d) throws JSONException {
        List<RoomSummary> rooms = new ArrayList<>();
        JSONArray arr = d.getJSONArray("rooms");
        for (int i = 0; i < arr.length(); i++) rooms.add(parseRoom(arr.getJSONObject(i)));
        notifyRoomListChanged(rooms);
    }

    private void handleRoomCreated(JSONObject d) throws JSONException {
        notifyRoomCreated(d.getLong("roomId"));
    }

    private void handleRoomCreateFail(JSONObject d) throws JSONException {
        notifyRoomCreateFailed(d.getString("reason"));
    }

    private void handleRoomJoinOk(JSONObject d) throws JSONException {
        notifyRoomJoinOk(d.getLong("id"));
    }

    private void handleRoomJoinFail(JSONObject d) throws JSONException {
        notifyRoomJoinFailed(d.getString("reason"));
    }

    private void handleRoomLeft(JSONObject d) throws JSONException {
        notifyRoomLeft(d.optString("reason", "left"));
    }

    private void handleRoomState(JSONObject d) {
        notifyRoomStateChanged(d);
    }

    private void handleMatchBegin(JSONObject d) throws JSONException {
        long matchId = d.getLong("matchId");
        List<MatchPeer> peers = new ArrayList<>();
        JSONArray arr = d.getJSONArray("peers");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            MatchPeer p = new MatchPeer();
            p.userId = o.getLong("userId");
            p.username = o.getString("username");
            p.publicIp = o.getString("publicIp");
            p.publicPort = o.getInt("publicPort");
            p.localIp = o.optString("localIp", "");
            p.slot = o.getInt("slot");

            // Use learned route if available
            LearnedRoute learned = learnedRoutes.get(p.userId);
            if (learned != null && learned.endpoint != null) {
                String advertised = p.publicIp + ":" + p.publicPort;
                if (!learned.endpoint.equals(advertised) &&
                    (System.currentTimeMillis() - learned.lastSeenMs) < LEARNED_ROUTE_TTL_MS) {
                    String[] parts = learned.endpoint.split(":");
                    if (parts.length == 2) {
                        Log.i(TAG, "Using learned route for " + p.username + ": " + learned.endpoint);
                        p.publicIp = parts[0];
                        p.publicPort = Integer.parseInt(parts[1]);
                    }
                }
            }
            peers.add(p);
        }

        // NAT punch-through
        punchPeerEndpoints(peers);

        notifyMatchBegin(matchId, peers);
    }

    private void handleMatchPeerLeft(JSONObject d) throws JSONException {
        notifyMatchPeerLeft(d.getLong("matchId"), d.getLong("userId"),
            d.optInt("slot", 0), d.optString("reason", "left"));
    }

    private void handlePingProbeReply(JSONObject d) throws JSONException {
        long userId = d.getLong("targetUserId");
        String endpoint = d.optString("targetEndpoint", "");
        sendProbeTo(userId, endpoint);
    }

    private void handlePingProbeIncoming(JSONObject d) throws JSONException {
        long userId = d.getLong("fromUserId");
        String endpoint = d.optString("fromEndpoint", "");
        if (userId != 0 && userId != selfUserId) sendProbeTo(userId, endpoint);
    }

    private void handleChatMsg(JSONObject d) throws JSONException {
        notifyChatMessage(d.getString("channel"), d.getLong("fromUserId"),
            d.getString("fromUsername"), d.getString("message"));
    }

    private void handleQuickMatchStatus(JSONObject d) throws JSONException {
        notifyQuickMatchStatus(d.getBoolean("searching"), d.getInt("queueSize"));
    }

    // --- UDP Anchor ---

    private void initUdpAnchor() {
        try {
            udpSocket = new DatagramSocket(0);
            udpSocket.setSoTimeout(100);
            anchorLocalPort = udpSocket.getLocalPort();
            Log.i(TAG, "UDP anchor on port " + anchorLocalPort);

            sendUdpRegister();

            keepaliveFuture = scheduler.scheduleAtFixedRate(
                this::sendUdpKeepalive, UDP_KEEPALIVE_INTERVAL_MS, UDP_KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);

            udpRunning = true;
            udpReceiveThread = new Thread(this::udpReceiveLoop, "lobby-udp-recv");
            udpReceiveThread.setDaemon(true);
            udpReceiveThread.start();
        } catch (Exception e) {
            Log.e(TAG, "UDP anchor init failed", e);
        }
    }

    /**
     * Closes the UDP anchor socket. Call this right before starting the
     * actual GekkoNet session (nativeStartLobbySession/nativeStartP2PSession) -
     * those bind a native socket to this same local port (to keep the
     * NAT-punched path the anchor already established), which fails with
     * "address already in use" if this Java socket is still holding it
     * open. The anchor's job (reporting our endpoint to the server so
     * peers can learn it) is already done by the time a match begins, so
     * there's nothing lost by closing it here.
     */
    public void stopUdpAnchorForGameSession() {
        closeUdpSocket();
    }

    private void closeUdpSocket() {
        udpRunning = false;
        if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close();
        if (udpReceiveThread != null) udpReceiveThread.interrupt();
    }

    private void sendUdpRegister() {
        if (selfUserId == 0 || udpSocket == null) return;
        try {
            byte[] data = buildAnchorPacket(ANCHOR_OP_REGISTER);
            InetAddress addr = InetAddress.getByName(udpAnchorHost);
            udpSocket.send(new DatagramPacket(data, data.length, addr, udpAnchorPort));
        } catch (Exception e) { Log.e(TAG, "UDP register failed", e); }
    }

    private void sendUdpKeepalive() {
        if (selfUserId == 0 || udpSocket == null || udpSocket.isClosed()) return;
        try {
            byte[] data = buildAnchorPacket(ANCHOR_OP_KEEPALIVE);
            InetAddress addr = InetAddress.getByName(udpAnchorHost);
            udpSocket.send(new DatagramPacket(data, data.length, addr, udpAnchorPort));
        } catch (Exception e) { /* ignore */ }
    }

    private byte[] buildAnchorPacket(byte op) {
        ByteBuffer buf = ByteBuffer.allocate(13);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(ANCHOR_MAGIC);
        buf.put(op);
        buf.putLong(selfUserId);
        return buf.array();
    }

    private void udpReceiveLoop() {
        byte[] buf = new byte[2048];
        while (udpRunning && udpSocket != null && !udpSocket.isClosed()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                udpSocket.receive(pkt);
                if (pkt.getLength() < 5) continue;
                if (buf[0] != 'R' || buf[1] != 'M' || buf[2] != 'G' || buf[3] != 'K') continue;

                byte op = buf[4];
                if (op == ANCHOR_OP_PROBE) {
                    handleUdpProbe(buf, pkt);
                } else if (op == ANCHOR_OP_PROBE_REPLY) {
                    handleUdpProbeReply(buf, pkt);
                }
            } catch (java.net.SocketTimeoutException e) { /* normal */ }
            catch (Exception e) {
                if (udpRunning) Log.e(TAG, "UDP receive error", e);
            }
        }
    }

    private void handleUdpProbe(byte[] buf, DatagramPacket pkt) {
        if (pkt.getLength() < PROBE_PACKET_SIZE) return;

        // Echo as PROBE_REPLY
        byte[] reply = new byte[PROBE_PACKET_SIZE];
        System.arraycopy(buf, 0, reply, 0, PROBE_PACKET_SIZE);
        reply[4] = ANCHOR_OP_PROBE_REPLY;
        ByteBuffer.wrap(reply, 5, 8).order(ByteOrder.BIG_ENDIAN).putLong(selfUserId);

        try {
            udpSocket.send(new DatagramPacket(reply, reply.length, pkt.getAddress(), pkt.getPort()));
        } catch (Exception e) { Log.e(TAG, "Probe reply failed", e); }

        // Learn route
        long fromUserId = ByteBuffer.wrap(buf, 5, 8).order(ByteOrder.BIG_ENDIAN).getLong();
        learnRoute(fromUserId, pkt.getAddress().getHostAddress(), pkt.getPort());
    }

    private void handleUdpProbeReply(byte[] buf, DatagramPacket pkt) {
        if (pkt.getLength() < PROBE_PACKET_SIZE) return;

        long echoerId = ByteBuffer.wrap(buf, 5, 8).order(ByteOrder.BIG_ENDIAN).getLong();
        learnRoute(echoerId, pkt.getAddress().getHostAddress(), pkt.getPort());

        long nonce = ByteBuffer.wrap(buf, 13, 8).order(ByteOrder.BIG_ENDIAN).getLong();
        ProbeInFlight probe = pendingProbes.remove(nonce);
        if (probe != null) {
            int rttMs = (int)(System.currentTimeMillis() - probe.sendMs);
            measuredPing.put(probe.targetUserId, rttMs);
            Log.i(TAG, "Ping to user " + probe.targetUserId + ": " + rttMs + "ms");
            notifyPingMeasured(probe.targetUserId, rttMs);
        }
    }

    private void learnRoute(long userId, String ip, int port) {
        if (userId == 0 || userId == selfUserId || port == 0) return;
        LearnedRoute route = new LearnedRoute();
        route.endpoint = ip + ":" + port;
        route.lastSeenMs = System.currentTimeMillis();
        learnedRoutes.put(userId, route);
    }

    // --- Ping Probes ---

    private void sendProbeTo(long userId, String endpoint) {
        if (endpoint == null || endpoint.isEmpty() || udpSocket == null) return;
        if (userId == 0 || userId == selfUserId) return;

        for (ProbeInFlight p : pendingProbes.values()) {
            if (p.targetUserId == userId) return; // already probing
        }

        String[] parts = endpoint.split(":");
        if (parts.length != 2) return;

        try {
            InetAddress addr = InetAddress.getByName(parts[0]);
            int port = Integer.parseInt(parts[1]);
            long nonce = random.nextLong();
            long now = System.currentTimeMillis();

            ProbeInFlight probe = new ProbeInFlight();
            probe.targetUserId = userId;
            probe.sendMs = now;
            probe.attemptSendMs = now;
            probe.endpoint = endpoint;
            probe.attempt = 1;
            probe.nextAttemptMs = now + PROBE_RETRY_INTERVAL_MS;
            pendingProbes.put(nonce, probe);

            sendProbeBurst(addr, port, nonce);
        } catch (Exception e) {
            Log.e(TAG, "sendProbeTo failed", e);
        }
    }

    private void sendProbeBurst(InetAddress addr, int port, long nonce) {
        if (udpSocket == null || udpSocket.isClosed()) return;
        try {
            ByteBuffer buf = ByteBuffer.allocate(PROBE_PACKET_SIZE);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.put(ANCHOR_MAGIC);
            buf.put(ANCHOR_OP_PROBE);
            buf.putLong(selfUserId);
            buf.putLong(nonce);
            byte[] data = buf.array();

            for (int i = 0; i < PROBE_BURST; i++) {
                udpSocket.send(new DatagramPacket(data, data.length, addr, port));
            }
        } catch (Exception e) { Log.e(TAG, "sendProbeBurst failed", e); }
    }

    // --- NAT Punch ---

    private void punchPeerEndpoints(List<MatchPeer> peers) {
        if (udpSocket == null || udpSocket.isClosed() || selfUserId == 0) return;

        byte[] punchData = buildAnchorPacket(ANCHOR_OP_PUNCH);

        for (MatchPeer p : peers) {
            if (p.userId == selfUserId) continue;
            if (p.publicIp == null || p.publicIp.isEmpty() || p.publicPort == 0) continue;
            try {
                InetAddress addr = InetAddress.getByName(p.publicIp);
                Log.i(TAG, "Punching " + p.username + " at " + p.publicIp + ":" + p.publicPort);
                for (int i = 0; i < ANCHOR_PUNCH_BURST; i++) {
                    udpSocket.send(new DatagramPacket(punchData, punchData.length, addr, p.publicPort));
                }
            } catch (Exception e) { Log.e(TAG, "Punch failed for " + p.username, e); }
        }
    }

    // --- Heartbeat ---

    private void sendHeartbeat() {
        if (state != ConnectionState.CONNECTED) return;
        try {
            JSONObject d = new JSONObject();
            d.put("away", false);
            sendEnvelope("HEARTBEAT", d);
        } catch (JSONException e) { /* ignore */ }
    }

    // --- Utility ---

    private void stopTimers() {
        if (heartbeatFuture != null) { heartbeatFuture.cancel(false); heartbeatFuture = null; }
        if (keepaliveFuture != null) { keepaliveFuture.cancel(false); keepaliveFuture = null; }
        if (httpKeepaliveFuture != null) { httpKeepaliveFuture.cancel(false); httpKeepaliveFuture = null; }
    }

    private String detectLocalIPv4() {
        try {
            for (java.net.NetworkInterface iface : java.util.Collections.list(
                    java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (java.net.InetAddress addr : java.util.Collections.list(iface.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    private LobbyUser parseUser(JSONObject o) throws JSONException {
        LobbyUser u = new LobbyUser();
        u.id = o.getLong("id");
        u.username = o.getString("username");
        u.state = o.optString("state", "");
        u.region = o.optString("region", "");
        u.country = o.optString("country", "");
        u.clientVersion = o.optString("clientVersion", "");
        u.connection = o.optString("connection", "");
        u.pingToServer = o.optInt("pingToServer", 0);
        u.currentRoomId = o.optLong("currentRoomId", 0);
        u.currentRoomName = o.optString("currentRoomName", "");
        return u;
    }

    private RoomSummary parseRoom(JSONObject o) throws JSONException {
        RoomSummary r = new RoomSummary();
        r.id = o.getLong("id");
        r.name = o.getString("name");
        r.hostId = o.getLong("hostId");
        r.hostName = o.optString("hostName", "");
        JSONObject rom = o.optJSONObject("rom");
        if (rom != null) {
            r.romName = rom.optString("name", "");
            r.romMd5 = rom.optString("md5", "");
        }
        // RMG-K's real lobby server sends "players" as a JSON array of seat
        // objects ({slot, username, userId}), not a plain count - getInt()
        // would throw here and silently break every ROOM_LIST update. Accept
        // either shape.
        Object playersField = o.opt("players");
        if (playersField instanceof JSONArray) {
            JSONArray playerArr = (JSONArray) playersField;
            r.players = playerArr.length();
            if (r.playerNames.isEmpty()) {
                for (int i = 0; i < playerArr.length(); i++) {
                    JSONObject p = playerArr.optJSONObject(i);
                    if (p != null) r.playerNames.add(p.optString("username", ""));
                }
            }
        } else {
            r.players = o.optInt("players", 0);
        }
        r.maxPlayers = o.getInt("maxPlayers");
        r.state = o.optString("state", "");
        r.hasPassword = o.optBoolean("hasPassword", false);
        JSONArray names = o.optJSONArray("playerNames");
        if (names != null) for (int i = 0; i < names.length(); i++) r.playerNames.add(names.getString(i));
        return r;
    }

    private void setState(ConnectionState newState) {
        if (state == newState) return;
        state = newState;
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onStateChanged(newState); });
    }

    // --- Notifications (main thread) ---

    private void notifyConnectError(String e) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onConnectError(e); });
    }

    private void notifyHelloOk(long uid, String ip, String r) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onHelloOk(uid, ip, r); });
    }

    private void notifyHelloFailed(String r) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onHelloFailed(r); });
    }

    private void notifyPresenceFull() {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onPresenceFull(new HashMap<>(users)); });
    }

    private void notifyUserAdded(long id) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onUserAdded(id); });
    }

    private void notifyUserRemoved(long id) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onUserRemoved(id); });
    }

    private void notifyRoomListChanged(List<RoomSummary> rooms) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onRoomListChanged(rooms); });
    }

    private void notifyRoomCreated(long id) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onRoomCreated(id); });
    }

    private void notifyRoomCreateFailed(String r) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onRoomCreateFailed(r); });
    }

    private void notifyRoomJoinOk(long id) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onRoomJoinOk(id); });
    }

    private void notifyRoomJoinFailed(String r) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onRoomJoinFailed(r); });
    }

    private void notifyRoomLeft(String r) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onRoomLeft(r); });
    }

    private void notifyRoomStateChanged(JSONObject d) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onRoomStateChanged(d); });
    }

    private void notifyMatchBegin(long mid, List<MatchPeer> peers) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onMatchBegin(mid, peers); });
    }

    private void notifyMatchPeerLeft(long mid, long uid, int slot, String r) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onMatchPeerLeft(mid, uid, slot, r); });
    }

    private void notifyPingMeasured(long uid, int rtt) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onPingMeasured(uid, rtt); });
    }

    private void notifyChatMessage(String ch, long uid, String name, String msg) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onChatMessage(ch, uid, name, msg); });
    }

    private void notifyQuickMatchStatus(boolean s, int q) {
        mainHandler.post(() -> { for (LobbyListener l : listeners) l.onQuickMatchStatus(s, q); });
    }
}
