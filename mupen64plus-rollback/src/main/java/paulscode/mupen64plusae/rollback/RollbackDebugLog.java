package paulscode.mupen64plusae.rollback;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Cross-process debug log for the Rollback Netplay feature.
 *
 * GameActivity runs in a separate OS process (":EmulationProcess", see
 * AndroidManifest.xml) from the rest of the app, so in-memory log buffers
 * don't work here (each process would have its own disconnected copy -
 * the same problem RollbackGameBridge had before it was switched to
 * broadcasts). This writes plain lines to a shared file instead, which
 * both processes can append to and which RollbackNetplayActivity can
 * read/display/copy on demand - no adb or logcat access needed.
 *
 * File lives at getExternalFilesDir(null)/rollback_debug.log, reachable
 * with any file manager app if needed, but the intended way to read it
 * is the "View Debug Log" option in the Rollback Netplay screen.
 */
public final class RollbackDebugLog {

    private static final String TAG = "RollbackDebugLog";
    private static final String FILE_NAME = "rollback_debug.log";
    private static final int MAX_FILE_SIZE_BYTES = 512 * 1024; // trim if it grows past this

    private RollbackDebugLog() { }

    public static void log(Context context, String tag, String message) {
        Log.i(tag, message); // still goes to logcat too, in case adb IS available

        try {
            File file = logFile(context);
            trimIfTooLarge(file);

            String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + " [" + android.os.Process.myPid() + "] " + tag + ": " + message + "\n";

            try (FileWriter fw = new FileWriter(file, true)) {
                fw.write(line);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write debug log", e);
        }
    }

    public static void error(Context context, String tag, String message, Throwable t) {
        String full = message + (t != null ? " - " + t.getClass().getSimpleName() + ": " + t.getMessage() : "");
        log(context, tag, "ERROR: " + full);
    }

    public static String readAll(Context context) {
        try {
            File file = logFile(context);
            if (!file.exists()) return "(no log entries yet)";
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.length() > 0 ? sb.toString() : "(no log entries yet)";
        } catch (Exception e) {
            return "(failed to read log: " + e.getMessage() + ")";
        }
    }

    public static void clear(Context context) {
        try {
            File file = logFile(context);
            if (file.exists()) {
                new FileWriter(file, false).close();
            }
        } catch (IOException e) { /* best effort */ }
    }

    private static File logFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        return new File(dir, FILE_NAME);
    }

    private static void trimIfTooLarge(File file) {
        try {
            if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
                // Keep only the last half - cheap way to bound growth
                // without needing a proper ring buffer.
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    long keepFrom = raf.length() - (MAX_FILE_SIZE_BYTES / 2);
                    raf.seek(Math.max(0, keepFrom));
                    byte[] rest = new byte[(int) (raf.length() - raf.getFilePointer())];
                    raf.readFully(rest);
                    try (FileWriter fw = new FileWriter(file, false)) {
                        fw.write(new String(rest, "UTF-8"));
                    }
                }
            }
        } catch (Exception e) { /* best effort - don't let trimming break logging */ }
    }
}
