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
