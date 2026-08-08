package paulscode.mupen64plusae.rollback;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes any uncaught exception to a plain text file the user can open with
 * any file manager app, no adb/PC/logcat access required. Install this as
 * early as possible in an Activity/Service's onCreate() (ideally before
 * even calling super.onCreate()) so it is guaranteed to be active before
 * anything that could crash - such as inflating a layout - runs.
 *
 * The file ends up at:
 *   /Android/data/<applicationId>/files/rollback_crash.txt
 * which is reachable from stock file manager apps (e.g. "Files by Google",
 * or the built-in Files app) without needing storage permissions, since
 * getExternalFilesDir() is always app-accessible on modern Android.
 */
public final class RollbackCrashLogger {

    private static final String TAG = "RollbackCrashLogger";
    private static final String FILE_NAME = "rollback_crash.txt";
    private static boolean installed = false;

    private RollbackCrashLogger() { }

    public static synchronized void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;

        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previousHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashFile(appContext, thread, throwable);
            } catch (Throwable loggingFailure) {
                // Never let the crash logger itself hide the real crash.
                Log.e(TAG, "Failed to write crash log", loggingFailure);
            }

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            } else {
                Runtime.getRuntime().exit(1);
            }
        });
    }

    private static void writeCrashFile(Context appContext, Thread thread, Throwable throwable) {
        File dir = appContext.getExternalFilesDir(null);
        if (dir == null) {
            dir = appContext.getFilesDir();
        }
        File file = new File(dir, FILE_NAME);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Rollback Netplay crash");
        pw.println("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("Thread: " + thread.getName());
        pw.println();
        throwable.printStackTrace(pw);
        pw.flush();

        try (FileWriter fw = new FileWriter(file, false)) {
            fw.write(sw.toString());
        } catch (Exception e) {
            Log.e(TAG, "Could not write " + file.getAbsolutePath(), e);
        }

        Log.e(TAG, "Uncaught exception written to " + file.getAbsolutePath(), throwable);
    }
}
