package paulscode.mupen64plusae.rollback;

import android.util.Log;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Integrates rollback netcode with the AE's CoreInterface.
 * Handles the transition from normal emulation to rollback mode.
 *
 * Flow:
 * 1. AE opens ROM normally (coreInit → coreOpenRom)
 * 2. Match starts → RollbackCoreBridge.startRollbackExecution()
 * 3. This sets up deterministic mode, input callbacks, then calls M64CMD_ROLLBACK_EXECUTE
 * 4. The core runs the emulation loop with rollback support
 * 5. Video plugin renders to the existing GL context (same as normal emulation)
 * 6. When match ends → execution returns, normal emulation can resume
 */
public class RollbackCoreBridge {

    private static final String TAG = "RollbackCoreBridge";

    // Core library loaded independently (same .so as the AE uses)
    private static RollbackCoreLibrary sCoreLib;

    // JNA callbacks (must be kept as fields to prevent GC)
    private static RollbackJnaTypes.BeginFrameCallback sBeginFrameCallback;
    private static RollbackJnaTypes.EndFrameCallback sEndFrameCallback;
    private static RollbackJnaTypes.RollbackExecuteCallbacks sExecuteCallbacks;

    // State
    private static volatile boolean sRollbackActive = false;

    /**
     * Initialize by loading the core library.
     * Call this after the AE has loaded the core (so the .so is already in memory).
     */
    public static void init() {
        try {
            sCoreLib = Native.load("mupen64plus-core", RollbackCoreLibrary.class);
            Log.i(TAG, "RollbackCoreBridge initialized (core library loaded)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load core library", e);
        }
    }

    /**
     * Start rollback execution.
     * This replaces M64CMD_EXECUTE with M64CMD_ROLLBACK_EXECUTE.
     * Blocks until the match ends (call from a background thread).
     *
     * @param beginFrameCalledByCore Called by the core at the start of each frame
     * @return true if execution completed successfully
     */
    public static boolean startRollbackExecution() {
        if (sCoreLib == null) {
            Log.e(TAG, "Core library not loaded");
            return false;
        }

        if (sRollbackActive) {
            Log.e(TAG, "Rollback execution already active");
            return false;
        }

        sRollbackActive = true;
        Log.i(TAG, "Starting rollback execution");

        try {
            // Set up JNA callbacks
            sBeginFrameCallback = userData -> {
                // This is called by the core at the start of each frame
                // Return 1 to continue, 0 to stop
                if (RollbackNative.nativeIsSessionActive()) {
                    return 1;
                }
                return 0;
            };

            sEndFrameCallback = userData -> {
                return 1;
            };

            // Create the execute callbacks struct
            sExecuteCallbacks = new RollbackJnaTypes.RollbackExecuteCallbacks();
            sExecuteCallbacks.user_data = null;
            sExecuteCallbacks.begin_frame = sBeginFrameCallback;
            sExecuteCallbacks.end_frame = sEndFrameCallback;
            sExecuteCallbacks.pace_before_present = null;
            sExecuteCallbacks.pacing_trace_enabled = 0;
            sExecuteCallbacks.write();

            // Call M64CMD_ROLLBACK_EXECUTE through the core library
            int result = sCoreLib.CoreDoCommand(
                RollbackJnaTypes.M64CMD_ROLLBACK_EXECUTE,
                0,
                sExecuteCallbacks.getPointer()
            );

            boolean success = (result == RollbackJnaTypes.M64ERR_SUCCESS);
            Log.i(TAG, "Rollback execution ended: " + (success ? "success" : "error " + result));
            return success;

        } catch (Exception e) {
            Log.e(TAG, "Rollback execution failed", e);
            return false;
        } finally {
            sRollbackActive = false;
        }
    }

    /**
     * Stop the current rollback execution.
     * The core will stop at the next frame boundary.
     */
    public static void stopRollbackExecution() {
        RollbackNative.nativeRequestStop();
    }

    /**
     * Check if rollback execution is currently active.
     */
    public static boolean isRollbackActive() {
        return sRollbackActive;
    }

    /**
     * Set up rollback mode before starting execution.
     * Call this after ROM is loaded but before startRollbackExecution().
     */
    public static boolean setupRollbackMode(int players, int inputSize) {
        if (sCoreLib == null) {
            Log.e(TAG, "Core library not loaded");
            return false;
        }

        // Set deterministic mode
        int detResult = sCoreLib.CoreDoCommand(
            RollbackJnaTypes.M64CMD_ROLLBACK_SET_DETERMINISTIC, 1, null);
        if (detResult != RollbackJnaTypes.M64ERR_SUCCESS) {
            Log.e(TAG, "Failed to set deterministic mode");
            return false;
        }

        // Set input players
        int playersResult = sCoreLib.CoreDoCommand(
            RollbackJnaTypes.M64CMD_ROLLBACK_SET_INPUT_PLAYERS, players, null);
        if (playersResult != RollbackJnaTypes.M64ERR_SUCCESS) {
            Log.e(TAG, "Failed to set input players");
            return false;
        }

        Log.i(TAG, "Rollback mode configured: players=" + players + " inputSize=" + inputSize);
        return true;
    }
}
