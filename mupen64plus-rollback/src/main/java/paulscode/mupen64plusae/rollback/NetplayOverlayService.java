package paulscode.mupen64plusae.rollback;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Overlay service that shows network statistics during rollback netplay.
 * Shows ping, frames ahead, packet loss, etc.
 */
public class NetplayOverlayService extends Service {

    private static final String TAG = "NetplayOverlay";
    private WindowManager windowManager;
    private LinearLayout overlayView;
    private TextView pingText;
    private TextView framesAheadText;
    private TextView statusText;
    private Handler handler;
    private Runnable updateRunnable;
    private volatile boolean running;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        createOverlay();
        startUpdating();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) { /* ignore */ }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createOverlay() {
        overlayView = new LinearLayout(this);
        overlayView.setOrientation(LinearLayout.VERTICAL);
        overlayView.setBackgroundColor(0xCC1A1A1A);
        overlayView.setPadding(16, 8, 16, 8);

        statusText = new TextView(this);
        statusText.setText("● ONLINE");
        statusText.setTextColor(0xFF00DFDF);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusText.setTypeface(null, android.graphics.Typeface.BOLD);
        overlayView.addView(statusText);

        pingText = new TextView(this);
        pingText.setText("Ping: --- ms");
        pingText.setTextColor(0xFFFFFFFF);
        pingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        overlayView.addView(pingText);

        framesAheadText = new TextView(this);
        framesAheadText.setText("Ahead: 0.0");
        framesAheadText.setTextColor(0xFF9C9897);
        framesAheadText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        overlayView.addView(framesAheadText);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 16;
        params.y = 100;

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add overlay", e);
        }
    }

    private void startUpdating() {
        running = true;
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                updateStats();
                handler.postDelayed(this, 500); // Update every 500ms
            }
        };
        handler.post(updateRunnable);
    }

    private void updateStats() {
        try {
            // Get stats from the native layer
            float[] stats = RollbackNative.nativeGetNetworkStats(1);
            float framesAhead = RollbackNative.nativeGetFramesAhead();

            if (stats != null && stats.length >= 5) {
                float kbSent = stats[0];
                float kbRecv = stats[1];
                float lastPing = stats[2];
                float avgPing = stats[3];
                float jitter = stats[4];

                // Update ping
                int ping = (int) lastPing;
                if (ping >= 0) {
                    pingText.setText("Ping: " + ping + " ms");
                    if (ping < 50) {
                        pingText.setTextColor(0xFF00DFDF); // Good - cyan
                        statusText.setText("● ONLINE");
                        statusText.setTextColor(0xFF00DFDF);
                    } else if (ping < 100) {
                        pingText.setTextColor(0xFFFFFF00); // OK - yellow
                        statusText.setText("● ONLINE");
                        statusText.setTextColor(0xFFFFFF00);
                    } else {
                        pingText.setTextColor(0xFFFF4444); // Bad - red
                        statusText.setText("● HIGH PING");
                        statusText.setTextColor(0xFFFF4444);
                    }
                }

                // Update frames ahead
                framesAheadText.setText(String.format("Ahead: %.1f | %.1f KB/s ↓", framesAhead, kbRecv));
            }

            // Check if session is still active
            if (!RollbackNative.nativeIsSessionActive()) {
                statusText.setText("● DISCONNECTED");
                statusText.setTextColor(0xFFFF4444);
            }

            // Check for desync
            int desyncFrame = RollbackNative.nativeGetLastDesyncFrame();
            if (desyncFrame >= 0) {
                statusText.setText("● DESYNC @ frame " + desyncFrame);
                statusText.setTextColor(0xFFFF4444);
            }
        } catch (Exception e) {
            // JNI not loaded or error
        }
    }

    /**
     * Show a desync warning.
     */
    public void showDesyncWarning(int frame, int localChecksum, int remoteChecksum) {
        handler.post(() -> {
            statusText.setText("● DESYNC @ frame " + frame);
            statusText.setTextColor(0xFFFF4444);
            pingText.setText("Local: " + Integer.toHexString(localChecksum));
            framesAheadText.setText("Remote: " + Integer.toHexString(remoteChecksum));
        });
    }
}
