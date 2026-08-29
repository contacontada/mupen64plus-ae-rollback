package paulscode.mupen64plusae.rollback;

/**
 * JNI bridge to GekkoNet rollback netcode.
 * Based on RMG-K's rollback implementation (Jay-Day/RMG-K).
 */
public class RollbackNative {

    static {
        System.loadLibrary("mupen64plus-rollback");
    }

    /**
     * Callback interface for rollback events.
     */
    public interface RollbackCallback {
        void onSessionEvent(int type, int data);
        void onLog(String message);
    }

    // Core API is linked directly
    public static native boolean nativeInit();
    public static native void nativeSetCallback(RollbackCallback callback);

    /**
     * Installs a native (C/C++) crash handler that writes a minimal
     * marker (signal, faulting address, timestamp) to the given file path
     * before the process dies, then re-raises so the OS's own crash
     * reporting still happens unchanged. RollbackCrashLogger only sees
     * uncaught *Java* exceptions - a native crash takes the whole process
     * down before any Java code, including that handler, can run, and
     * looks from the outside exactly like the match silently freezing.
     * Call this once, as early as possible (RollbackNetplayService.onCreate()),
     * with a path under a directory the app can already write to, e.g.
     * new File(getExternalFilesDir(null), "rollback_native_crash.txt").
     */
    public static native void nativeSetCrashLogPath(String path);

    // Session management
    public static native boolean nativeStartP2PSession(
        String gameName, int players, int inputSize,
        int localPlayer, int localPort,
        String remoteIp, int remotePort,
        int localDelay, int predictionWindow);

    public static native boolean nativeStartLobbySession(
        String gameName, int players, int inputSize,
        int localPlayer, int localPort,
        int[] remoteSlots, String[] remoteIps, int[] remotePorts,
        int localDelay, int predictionWindow);

    public static native boolean nativeExecute();

    /** Human-readable reason for the last nativeStartLobbySession()/
     * nativeStartP2PSession() failure - since those only return a
     * boolean, this is what lets the reason actually reach the screen. */
    public static native String nativeGetLastError();
    public static native void nativeCloseSession();
    public static native void nativeRequestStop();

    // State queries
    public static native boolean nativeIsSessionActive();
    public static native boolean nativeIsExecuting();
    public static native float[] nativeGetNetworkStats(int player);
    public static native float nativeGetFramesAhead();

    // Player management
    public static native void nativeDisconnectPlayer(int handle);

    // Desync detection
    public static native int nativeGetLastDesyncFrame();
}
