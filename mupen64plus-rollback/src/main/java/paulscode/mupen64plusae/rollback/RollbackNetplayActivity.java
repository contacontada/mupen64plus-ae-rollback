package paulscode.mupen64plusae.rollback;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rollback Netplay Activity with Mupen64Plus AE-style UI.
 * Dark theme, Material Design, sidebar navigation.
 */
public class RollbackNetplayActivity extends AppCompatActivity {

    private static final String TAG = "RollbackNetplay";

    private RollbackNetplayService netplayService;
    private boolean serviceBound = false;

    // UI
    private DrawerLayout drawerLayout;
    private TextView statusText;
    private TextView sidebarTitle;
    private TextView sidebarSubtitle;

    // Connect panel
    private View connectPanel;
    private TextInputEditText serverUrlEdit;
    private TextInputEditText usernameEdit;
    private MaterialButton connectBtn;
    private MaterialButton directP2PBtn;

    // Room panel
    private View roomPanel;
    private RecyclerView roomRecyclerView;
    private TextView roomEmptyText;
    private MaterialButton createRoomBtn;
    private MaterialButton quickMatchBtn;

    // Match panel
    private View matchPanel;
    private TextView matchStatusText;
    private TextView matchSubStatusText;
    private ProgressBar matchProgress;
    private RecyclerView playerRecyclerView;
    private MaterialButton matchStartBtn;
    private MaterialButton matchDropBtn;
    private MaterialButton disconnectBtn;

    // Sidebar
    private ListView sidebarListView;

    // Adapters
    private RoomAdapter roomAdapter;
    private PlayerAdapter playerAdapter;

    // Data
    private List<RmgkLobbyClient.RoomSummary> currentRooms = new ArrayList<>();
    private List<RmgkLobbyClient.MatchPeer> currentPeers = new ArrayList<>();
    private String romMd5 = "";
    private String romName = "";

    public static void launch(Context context, String romMd5, String romName) {
        Intent intent = new Intent(context, RollbackNetplayActivity.class);
        intent.putExtra("ROM_MD5", romMd5 != null ? romMd5 : "");
        intent.putExtra("ROM_NAME", romName != null ? romName : "");
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rollback_netplay);

        // Get ROM info
        Intent intent = getIntent();
        if (intent != null) {
            romMd5 = intent.getStringExtra("ROM_MD5");
            if (romMd5 == null) romMd5 = "";
            romName = intent.getStringExtra("ROM_NAME");
            if (romName == null) romName = "";
        }

        initViews();
        initToolbar();
        initSidebar();
        initRecyclerViews();

        // Bind service
        Intent serviceIntent = new Intent(this, RollbackNetplayService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) unbindService(serviceConnection);
        super.onDestroy();
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        statusText = findViewById(R.id.statusText);
        sidebarTitle = findViewById(R.id.sidebarTitle);
        sidebarSubtitle = findViewById(R.id.sidebarSubtitle);

        // Connect panel
        connectPanel = findViewById(R.id.connectPanel);
        serverUrlEdit = findViewById(R.id.serverUrlEdit);
        usernameEdit = findViewById(R.id.usernameEdit);
        connectBtn = findViewById(R.id.connectBtn);
        directP2PBtn = findViewById(R.id.directP2PBtn);

        // Load saved settings
        String savedUrl = RollbackSettingsActivity.getServerUrl(this);
        String savedName = RollbackSettingsActivity.getUsername(this);
        if (!savedUrl.isEmpty()) serverUrlEdit.setText(savedUrl);
        if (!savedName.isEmpty()) usernameEdit.setText(savedName);

        connectBtn.setOnClickListener(v -> onConnect());
        directP2PBtn.setOnClickListener(v -> onDirectP2P());

        // Room panel
        roomPanel = findViewById(R.id.roomPanel);
        roomRecyclerView = findViewById(R.id.roomRecyclerView);
        roomEmptyText = findViewById(R.id.roomEmptyText);
        createRoomBtn = findViewById(R.id.createRoomBtn);
        quickMatchBtn = findViewById(R.id.quickMatchBtn);

        createRoomBtn.setOnClickListener(v -> onCreateRoom());
        quickMatchBtn.setOnClickListener(v -> onQuickMatch());

        // Match panel
        matchPanel = findViewById(R.id.matchPanel);
        matchStatusText = findViewById(R.id.matchStatusText);
        matchSubStatusText = findViewById(R.id.matchSubStatusText);
        matchProgress = findViewById(R.id.matchProgress);
        playerRecyclerView = findViewById(R.id.playerRecyclerView);
        matchStartBtn = findViewById(R.id.matchStartBtn);
        matchDropBtn = findViewById(R.id.matchDropBtn);
        disconnectBtn = findViewById(R.id.disconnectBtn);

        matchStartBtn.setOnClickListener(v -> onStartMatch());
        matchDropBtn.setOnClickListener(v -> onLeaveRoom());
        disconnectBtn.setOnClickListener(v -> onDisconnect());

        // Sidebar
        sidebarListView = findViewById(R.id.sidebarListView);
    }

    private void initToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Rollback Netplay");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeAsUpIndicator(android.R.drawable.ic_menu_sort_by_size);
        }
        toolbar.setNavigationOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(findViewById(R.id.sidebarListView).getParent() instanceof View ?
                    (View) findViewById(R.id.sidebarListView).getParent() : drawerLayout)) {
                drawerLayout.closeDrawers();
            } else {
                drawerLayout.openDrawer(findViewById(R.id.sidebarListView).getParent() instanceof View ?
                    (View) findViewById(R.id.sidebarListView).getParent() : drawerLayout);
            }
        });
    }

    private void initSidebar() {
        String[] menuItems = {
            "Browse Rooms", "Search for games and players",
            "Quick Match", "Auto-match with another player",
            "Create Room", "Host a new game room",
            "Direct P2P", "Connect directly by IP address",
            "Settings", "Netplay configuration"
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_list_item_2, android.R.id.text1, menuItems) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = view.findViewById(android.R.id.text1);
                TextView text2 = view.findViewById(android.R.id.text2);
                text1.setTextColor(0xFFFFFFFF);
                text2.setTextColor(0xFF9C9897);
                // Even positions are titles, odd are subtitles
                if (position % 2 == 0) {
                    text1.setText(menuItems[position]);
                    text2.setText(menuItems[position + 1]);
                    text2.setVisibility(View.VISIBLE);
                } else {
                    text2.setVisibility(View.GONE);
                    text1.setVisibility(View.GONE);
                }
                return view;
            }

            @Override
            public int getCount() {
                return menuItems.length / 2;
            }

            @Override
            public String getItem(int position) {
                return menuItems[position * 2];
            }
        };
        sidebarListView.setAdapter(adapter);
        sidebarListView.setOnItemClickListener((parent, view, position, id) -> {
            drawerLayout.closeDrawers();
            switch (position) {
                case 0: showPanel("room"); break;
                case 1: onQuickMatch(); break;
                case 2: onCreateRoom(); break;
                case 3: onDirectP2P(); break;
                case 4: RollbackSettingsActivity.launch(RollbackNetplayActivity.this); break;
            }
        });
    }

    private void initRecyclerViews() {
        roomAdapter = new RoomAdapter();
        roomRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        roomRecyclerView.setAdapter(roomAdapter);

        playerAdapter = new PlayerAdapter();
        playerRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        playerRecyclerView.setAdapter(playerAdapter);
    }

    // --- Service connection ---

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            RollbackNetplayService.LocalBinder binder = (RollbackNetplayService.LocalBinder) service;
            netplayService = binder.getService();
            serviceBound = true;
            netplayService.addListener(netplayListener);

            // Apply settings
            netplayService.setLocalDelay(RollbackSettingsActivity.getLocalDelay(RollbackNetplayActivity.this));
            netplayService.setPredictionWindow(RollbackSettingsActivity.getPredictionWindow(RollbackNetplayActivity.this));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    // --- Netplay listener ---

    private final RollbackNetplayService.NetplayListener netplayListener =
        new RollbackNetplayService.NetplayListener() {

        @Override
        public void onConnected(long userId) {
            runOnUiThread(() -> {
                sidebarSubtitle.setText("Connected (ID: " + userId + ")");
                statusText.setText("Connected to lobby");
                statusText.setVisibility(View.VISIBLE);
                disconnectBtn.setVisibility(View.VISIBLE);
                showPanel("room");
            });
        }

        @Override
        public void onDisconnected(String reason) {
            runOnUiThread(() -> {
                sidebarSubtitle.setText("Disconnected");
                statusText.setVisibility(View.GONE);
                disconnectBtn.setVisibility(View.GONE);
                showPanel("connect");
                Toast.makeText(RollbackNetplayActivity.this, "Disconnected: " + reason, Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onRoomListChanged(List<RmgkLobbyClient.RoomSummary> rooms) {
            runOnUiThread(() -> {
                currentRooms.clear();
                currentRooms.addAll(rooms);
                roomAdapter.notifyDataSetChanged();
                roomEmptyText.setVisibility(currentRooms.isEmpty() ? View.VISIBLE : View.GONE);
                roomRecyclerView.setVisibility(currentRooms.isEmpty() ? View.GONE : View.VISIBLE);
            });
        }

        @Override
        public void onRoomCreated(long roomId) {
            runOnUiThread(() -> {
                showPanel("match");
                matchStatusText.setText("Room Created");
                matchSubStatusText.setText("Waiting for players to join...");
                matchStartBtn.setVisibility(View.VISIBLE);
                matchProgress.setVisibility(View.GONE);
            });
        }

        @Override
        public void onRoomJoined(long roomId) {
            runOnUiThread(() -> {
                showPanel("match");
                matchStatusText.setText("Joined Room");
                matchSubStatusText.setText("Waiting for host to start...");
                matchStartBtn.setVisibility(View.GONE);
                matchProgress.setVisibility(View.GONE);
            });
        }

        @Override
        public void onRoomJoinFailed(String reason) {
            runOnUiThread(() -> Toast.makeText(RollbackNetplayActivity.this,
                "Join failed: " + reason, Toast.LENGTH_SHORT).show());
        }

        @Override
        public void onRoomLeft(String reason) {
            runOnUiThread(() -> {
                showPanel("room");
                Toast.makeText(RollbackNetplayActivity.this, "Left room", Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onRoomStateChanged(JSONObject data) {
            runOnUiThread(() -> {
                try {
                    String state = data.optString("state", "waiting");
                    if ("in_game".equals(state)) {
                        matchStatusText.setText("Match In Progress");
                        matchSubStatusText.setText("Game running with rollback netcode");
                        matchProgress.setVisibility(View.VISIBLE);
                    }
                } catch (Exception e) { /* ignore */ }
            });
        }

        @Override
        public void onMatchStarting(long matchId, List<RmgkLobbyClient.MatchPeer> peers) {
            runOnUiThread(() -> {
                currentPeers.clear();
                currentPeers.addAll(peers);
                playerAdapter.notifyDataSetChanged();
                matchStatusText.setText("Connecting...");
                matchSubStatusText.setText("NAT traversal and peer connection in progress");
                matchProgress.setVisibility(View.VISIBLE);
                matchStartBtn.setVisibility(View.GONE);
            });
        }

        @Override
        public void onMatchStarted() {
            runOnUiThread(() -> {
                matchStatusText.setText("Match Running");
                matchSubStatusText.setText("Rollback netcode active");
                matchProgress.setVisibility(View.GONE);
            });
        }

        @Override
        public void onMatchFinished() {
            runOnUiThread(() -> {
                matchStatusText.setText("Match Finished");
                matchSubStatusText.setText("");
                matchProgress.setVisibility(View.GONE);
                showPanel("room");
            });
        }

        @Override
        public void onError(String error) {
            runOnUiThread(() -> Toast.makeText(RollbackNetplayActivity.this,
                error, Toast.LENGTH_LONG).show());
        }

        @Override
        public void onStatusChanged(String status) {
            runOnUiThread(() -> {
                statusText.setText(status);
                statusText.setVisibility(View.VISIBLE);
            });
        }

        @Override
        public void onPingMeasured(long userId, int rttMs) {
            runOnUiThread(() -> playerAdapter.notifyDataSetChanged());
        }

        @Override
        public void onChatMessage(String channel, long fromUserId, String fromUsername, String message) {
            runOnUiThread(() -> Toast.makeText(RollbackNetplayActivity.this,
                fromUsername + ": " + message, Toast.LENGTH_SHORT).show());
        }
    };

    // --- Actions ---

    private void onConnect() {
        String url = serverUrlEdit.getText().toString().trim();
        String username = usernameEdit.getText().toString().trim();
        if (url.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, "Enter server URL and username", Toast.LENGTH_SHORT).show();
            return;
        }
        netplayService.setPlayerName(username);
        List<String> romHashes = new ArrayList<>();
        romHashes.add(!romMd5.isEmpty() ? romMd5 : "00000000000000000000000000000000");
        netplayService.connectToLobby(url, romHashes);
    }

    private void onDirectP2P() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Direct P2P Connection");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        EditText ipEdit = new EditText(this);
        ipEdit.setHint("Remote IP address");
        ipEdit.setTextColor(0xFFFFFFFF);
        ipEdit.setHintTextColor(0xFF747273);
        layout.addView(ipEdit);

        EditText portEdit = new EditText(this);
        portEdit.setHint("Remote port");
        portEdit.setTextColor(0xFFFFFFFF);
        portEdit.setHintTextColor(0xFF747273);
        portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        portEdit.setText("4445");
        layout.addView(portEdit);

        builder.setView(layout);
        builder.setPositiveButton("Connect", (dialog, which) -> {
            String ip = ipEdit.getText().toString().trim();
            int port = Integer.parseInt(portEdit.getText().toString().trim());
            if (!ip.isEmpty() && port > 0) {
                netplayService.startDirectP2P(
                    !romName.isEmpty() ? romName : "N64 Game",
                    1, 4444, ip, port, 2);
                showPanel("match");
                matchStatusText.setText("P2P Connection");
                matchSubStatusText.setText("Connecting to " + ip + ":" + port);
                matchProgress.setVisibility(View.VISIBLE);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void onCreateRoom() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Create Room");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        EditText nameEdit = new EditText(this);
        nameEdit.setHint("Room Name");
        nameEdit.setTextColor(0xFFFFFFFF);
        nameEdit.setHintTextColor(0xFF747273);
        layout.addView(nameEdit);

        EditText romEdit = new EditText(this);
        romEdit.setHint("ROM Name");
        romEdit.setTextColor(0xFFFFFFFF);
        romEdit.setHintTextColor(0xFF747273);
        if (!romName.isEmpty()) romEdit.setText(romName);
        layout.addView(romEdit);

        Spinner maxPlayersSpinner = new Spinner(this);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, new String[]{"2 Players", "3 Players", "4 Players"});
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        maxPlayersSpinner.setAdapter(spinnerAdapter);
        layout.addView(maxPlayersSpinner);

        builder.setView(layout);
        builder.setPositiveButton("Create", (dialog, which) -> {
            String roomName = nameEdit.getText().toString().trim();
            String rName = romEdit.getText().toString().trim();
            int maxPlayers = maxPlayersSpinner.getSelectedItemPosition() + 2;
            if (roomName.isEmpty()) roomName = "Room";
            if (rName.isEmpty()) rName = "Unknown ROM";
            netplayService.createRoom(roomName, rName, romMd5, maxPlayers, 2, 7, null);
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void onQuickMatch() {
        String name = !romName.isEmpty() ? romName : "N64 Game";
        netplayService.quickMatch(name, romMd5);
        statusText.setText("Searching for match...");
    }

    private void onStartMatch() {
        netplayService.startMatch();
    }

    private void onLeaveRoom() {
        netplayService.leaveRoom();
        showPanel("room");
    }

    private void onDisconnect() {
        netplayService.disconnectFromLobby();
        showPanel("connect");
        sidebarSubtitle.setText("Disconnected");
        disconnectBtn.setVisibility(View.GONE);
    }

    private void showPanel(String panel) {
        connectPanel.setVisibility("connect".equals(panel) ? View.VISIBLE : View.GONE);
        roomPanel.setVisibility("room".equals(panel) ? View.VISIBLE : View.GONE);
        matchPanel.setVisibility("match".equals(panel) ? View.VISIBLE : View.GONE);
    }

    // --- RecyclerView Adapters ---

    private class RoomAdapter extends RecyclerView.Adapter<RoomAdapter.ViewHolder> {

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView roomName, roomGame, roomStatus, playerCount;
            ImageView roomIcon, passwordIcon;

            ViewHolder(View v) {
                super(v);
                roomIcon = v.findViewById(R.id.roomIcon);
                roomName = v.findViewById(R.id.roomName);
                roomGame = v.findViewById(R.id.roomGame);
                roomStatus = v.findViewById(R.id.roomStatus);
                playerCount = v.findViewById(R.id.playerCount);
                passwordIcon = v.findViewById(R.id.passwordIcon);
            }
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_lobby_room, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            RmgkLobbyClient.RoomSummary room = currentRooms.get(position);
            holder.roomName.setText(room.name);
            holder.roomGame.setText(room.romName + " | Host: " + room.hostName);
            holder.roomStatus.setText(room.state + " | " + room.players + "/" + room.maxPlayers + " players");
            holder.playerCount.setText(room.players + "/" + room.maxPlayers);
            holder.passwordIcon.setVisibility(room.hasPassword ? View.VISIBLE : View.GONE);

            holder.itemView.setOnClickListener(v -> {
                if (room.hasPassword) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(RollbackNetplayActivity.this);
                    builder.setTitle("Enter Password");
                    EditText passwordEdit = new EditText(RollbackNetplayActivity.this);
                    passwordEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    passwordEdit.setTextColor(0xFFFFFFFF);
                    builder.setView(passwordEdit);
                    builder.setPositiveButton("Join", (dialog, which) ->
                        netplayService.joinRoom(room.id, passwordEdit.getText().toString()));
                    builder.setNegativeButton("Cancel", null);
                    builder.show();
                } else {
                    netplayService.joinRoom(room.id, null);
                }
            });
        }

        @Override
        public int getItemCount() { return currentRooms.size(); }
    }

    private class PlayerAdapter extends RecyclerView.Adapter<PlayerAdapter.ViewHolder> {

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView playerSlot, playerName, playerSelf, playerEndpoint, pingText;
            ImageView playerIcon;

            ViewHolder(View v) {
                super(v);
                playerIcon = v.findViewById(R.id.playerIcon);
                playerSlot = v.findViewById(R.id.playerSlot);
                playerName = v.findViewById(R.id.playerName);
                playerSelf = v.findViewById(R.id.playerSelf);
                playerEndpoint = v.findViewById(R.id.playerEndpoint);
                pingText = v.findViewById(R.id.pingText);
            }
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_lobby_player, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            RmgkLobbyClient.MatchPeer peer = currentPeers.get(position);
            boolean isSelf = serviceBound &&
                peer.userId == netplayService.getLobbyClient().getSelfUserId();

            holder.playerSlot.setText("P" + peer.slot);
            holder.playerName.setText(peer.username);
            holder.playerSelf.setVisibility(isSelf ? View.VISIBLE : View.GONE);
            holder.playerEndpoint.setText(peer.publicIp + ":" + peer.publicPort);

            int ping = serviceBound ? netplayService.getLobbyClient().getMeasuredPing(peer.userId) : -1;
            holder.pingText.setText(ping >= 0 ? ping + "ms" : "---");

            // Color code ping
            if (ping >= 0) {
                if (ping < 50) holder.pingText.setTextColor(0xFF00DFDF); // good
                else if (ping < 100) holder.pingText.setTextColor(0xFFFFFF00); // ok
                else holder.pingText.setTextColor(0xFFFF4444); // bad
            }
        }

        @Override
        public int getItemCount() { return currentPeers.size(); }
    }
}
