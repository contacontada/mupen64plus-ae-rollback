package paulscode.mupen64plusae.rollback;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Settings activity for rollback netplay configuration.
 * Matches the AE's settings style.
 */
public class RollbackSettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "rollback_netplay_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_LOCAL_DELAY = "local_delay";
    private static final String KEY_PREDICTION_WINDOW = "prediction_window";
    private static final String KEY_AUTO_CONNECT = "auto_connect";
    private static final String KEY_SHOW_OVERLAY = "show_overlay";
    private static final String KEY_DESYNC_DETECTION = "desync_detection";

    private static final String DEFAULT_SERVER_URL = "wss://lobby.rmgk.example.com/ws";
    private static final int DEFAULT_DELAY = 2;
    private static final int DEFAULT_PREDICTION = 7;

    private TextInputEditText serverUrlEdit;
    private TextInputEditText usernameEdit;
    private TextInputEditText localDelayEdit;
    private TextInputEditText predictionWindowEdit;
    private SwitchMaterial autoConnectSwitch;
    private SwitchMaterial showOverlaySwitch;
    private SwitchMaterial desyncDetectionSwitch;

    public static void launch(Context context) {
        context.startActivity(new Intent(context, RollbackSettingsActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createLayout());
        loadSettings();
    }

    private ScrollView createLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF1A1A1A);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 32, 48, 32);

        // Title
        addSectionTitle(root, "Rollback Netplay Settings");
        addDescription(root, "Configure your rollback netplay connection and preferences.");

        // Server section
        addSectionTitle(root, "Server");
        serverUrlEdit = addTextInput(root, "Lobby Server URL", DEFAULT_SERVER_URL, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        // Player section
        addSectionTitle(root, "Player");
        usernameEdit = addTextInput(root, "Player Name", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);

        // Network section
        addSectionTitle(root, "Network");
        addDescription(root, "Local delay adds input lag to smooth out the connection. Higher values = more lag but less rollback.");
        localDelayEdit = addTextInput(root, "Local Delay (frames)", String.valueOf(DEFAULT_DELAY), InputType.TYPE_CLASS_NUMBER);
        addDescription(root, "Prediction window controls how many frames ahead the emulator predicts. Higher = smoother but more visual artifacts on rollback.");
        predictionWindowEdit = addTextInput(root, "Prediction Window (frames)", String.valueOf(DEFAULT_PREDICTION), InputType.TYPE_CLASS_NUMBER);

        // Toggles section
        addSectionTitle(root, "Options");
        autoConnectSwitch = addSwitch(root, "Auto-connect on startup", false);
        showOverlaySwitch = addSwitch(root, "Show network overlay during game", true);
        desyncDetectionSwitch = addSwitch(root, "Enable desync detection", true);

        // Save button
        MaterialButton saveBtn = new MaterialButton(this);
        saveBtn.setText("Save Settings");
        saveBtn.setBackgroundColor(0xFF00DFDF);
        saveBtn.setTextColor(0xFF1A1A1A);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 140);
        saveParams.topMargin = 32;
        saveBtn.setLayoutParams(saveParams);
        saveBtn.setOnClickListener(v -> {
            saveSettings();
            finish();
        });
        root.addView(saveBtn);

        // Reset button
        MaterialButton resetBtn = new MaterialButton(this);
        resetBtn.setText("Reset to Defaults");
        resetBtn.setBackgroundColor(0xFF424042);
        resetBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 140);
        resetParams.topMargin = 16;
        resetBtn.setLayoutParams(resetParams);
        resetBtn.setOnClickListener(v -> resetDefaults());
        root.addView(resetBtn);

        scroll.addView(root);
        return scroll;
    }

    private void addSectionTitle(LinearLayout parent, String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(0xFF00DFDF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = 32;
        params.bottomMargin = 8;
        title.setLayoutParams(params);
        parent.addView(title);
    }

    private void addDescription(LinearLayout parent, String text) {
        TextView desc = new TextView(this);
        desc.setText(text);
        desc.setTextColor(0xFF9C9897);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = 16;
        desc.setLayoutParams(params);
        parent.addView(desc);
    }

    private TextInputEditText addTextInput(LinearLayout parent, String hint, String defaultValue, int inputType) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxStrokeColor(0xFF00DFDF);
        layout.setHintTextColor(android.content.res.ColorStateList.valueOf(0xFF00DFDF));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = 16;
        layout.setLayoutParams(params);

        TextInputEditText edit = new TextInputEditText(this);
        edit.setText(defaultValue);
        edit.setTextColor(0xFFFFFFFF);
        edit.setHintTextColor(0xFF747273);
        edit.setInputType(inputType);
        layout.addView(edit);
        parent.addView(layout);
        return edit;
    }

    private SwitchMaterial addSwitch(LinearLayout parent, String text, boolean defaultValue) {
        SwitchMaterial sw = new SwitchMaterial(this);
        sw.setText(text);
        sw.setTextColor(0xFFFFFFFF);
        sw.setChecked(defaultValue);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = 16;
        sw.setLayoutParams(params);
        parent.addView(sw);
        return sw;
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        serverUrlEdit.setText(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
        usernameEdit.setText(prefs.getString(KEY_USERNAME, ""));
        localDelayEdit.setText(String.valueOf(prefs.getInt(KEY_LOCAL_DELAY, DEFAULT_DELAY)));
        predictionWindowEdit.setText(String.valueOf(prefs.getInt(KEY_PREDICTION_WINDOW, DEFAULT_PREDICTION)));
        autoConnectSwitch.setChecked(prefs.getBoolean(KEY_AUTO_CONNECT, false));
        showOverlaySwitch.setChecked(prefs.getBoolean(KEY_SHOW_OVERLAY, true));
        desyncDetectionSwitch.setChecked(prefs.getBoolean(KEY_DESYNC_DETECTION, true));
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrlEdit.getText().toString().trim())
            .putString(KEY_USERNAME, usernameEdit.getText().toString().trim())
            .putInt(KEY_LOCAL_DELAY, parseInt(localDelayEdit, DEFAULT_DELAY))
            .putInt(KEY_PREDICTION_WINDOW, parseInt(predictionWindowEdit, DEFAULT_PREDICTION))
            .putBoolean(KEY_AUTO_CONNECT, autoConnectSwitch.isChecked())
            .putBoolean(KEY_SHOW_OVERLAY, showOverlaySwitch.isChecked())
            .putBoolean(KEY_DESYNC_DETECTION, desyncDetectionSwitch.isChecked())
            .apply();
    }

    private void resetDefaults() {
        serverUrlEdit.setText(DEFAULT_SERVER_URL);
        usernameEdit.setText("");
        localDelayEdit.setText(String.valueOf(DEFAULT_DELAY));
        predictionWindowEdit.setText(String.valueOf(DEFAULT_PREDICTION));
        autoConnectSwitch.setChecked(false);
        showOverlaySwitch.setChecked(true);
        desyncDetectionSwitch.setChecked(true);
    }

    private int parseInt(TextInputEditText edit, int defaultVal) {
        try {
            return Integer.parseInt(edit.getText().toString().trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // Static helpers for reading settings from anywhere
    public static String getServerUrl(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    public static String getUsername(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_USERNAME, "");
    }

    public static int getLocalDelay(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_LOCAL_DELAY, DEFAULT_DELAY);
    }

    public static int getPredictionWindow(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_PREDICTION_WINDOW, DEFAULT_PREDICTION);
    }

    public static boolean getShowOverlay(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_SHOW_OVERLAY, true);
    }

    public static boolean getDesyncDetection(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_DESYNC_DETECTION, true);
    }
}
