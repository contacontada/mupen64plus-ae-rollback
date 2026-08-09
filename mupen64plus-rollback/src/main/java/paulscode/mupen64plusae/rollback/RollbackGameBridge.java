package paulscode.mupen64plusae.rollback;

import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges GameActivity (owns ROM loading / the real emulation core) and
 * RollbackNetplayService (drives an in-progress rollback session on a
 * background thread).
 *
 * Rollback mode needs the core running with the ROM loaded, but PAUSED
 * (not auto-playing in real time) before it can safely call
 * M64CMD_ROLLBACK_* commands or start single-stepping frames -
 * main_rollback_run_frame() in the native core assumes the emulation
 * thread is already inside run_device()'s blocking loop and paused, ready
 * to be single-stepped externally.
 *
 * Flow:
 *   1. RollbackNetplayService.startRollbackGame() launches GameActivity
 *      with EXTRA ROLLBACK_MODE=true, then calls waitForCoreReady().
 *   2. GameActivity loads the ROM/starts the core exactly like a normal
 *      game launch, but (because ROLLBACK_MODE is set) pauses instead of
 *      auto-resuming once the core reports started, then calls
 *      notifyCoreReady().
 *   3. RollbackNetplayService's waitForCoreReady() unblocks, and it
 *      proceeds to configure and run the rollback session.
 *   4. When the match ends, RollbackNetplayService calls
 *      notifyMatchEnded() so GameActivity can resume normal control (or
 *      close), and reset() clears state for the next session.
 */
public final class RollbackGameBridge {

    private static final String TAG = "RollbackGameBridge";

    public interface MatchEndListener {
        void onRollbackMatchEnded();
    }

    private static volatile CountDownLatch sReadyLatch = new CountDownLatch(1);
    private static final AtomicReference<String> sFailureReason = new AtomicReference<>(null);
    private static volatile boolean sRollbackSessionActive = false;
    private static volatile MatchEndListener sMatchEndListener;

    private RollbackGameBridge() { }

    /** Call before launching GameActivity for a new rollback session. */
    public static synchronized void beginNewSession() {
        sReadyLatch = new CountDownLatch(1);
        sFailureReason.set(null);
        sRollbackSessionActive = true;
    }

    /** Called by GameActivity once the core is loaded, paused, and ready. */
    public static void notifyCoreReady() {
        Log.i(TAG, "Core ready for rollback");
        sReadyLatch.countDown();
    }

    /** Called by GameActivity if ROM loading / core startup itself failed. */
    public static void notifyCoreStartFailed(String reason) {
        Log.e(TAG, "Core failed to start for rollback: " + reason);
        sFailureReason.set(reason != null ? reason : "unknown error");
        sReadyLatch.countDown();
    }

    /**
     * Blocks the calling thread (must NOT be the main/UI thread) until
     * GameActivity signals the core is ready, or the timeout elapses.
     *
     * @return null on success, or a human-readable failure reason.
     */
    public static String waitForCoreReady(long timeoutMs) {
        try {
            boolean signaled = sReadyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!signaled) {
                return "Timed out waiting for the game to start";
            }
            String failure = sFailureReason.get();
            return failure; // null if notifyCoreReady() was the one that fired
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for the game to start";
        }
    }

    public static boolean isRollbackSessionActive() {
        return sRollbackSessionActive;
    }

    public static void setMatchEndListener(MatchEndListener listener) {
        sMatchEndListener = listener;
    }

    /** Called by RollbackNetplayService once the match/session is over. */
    public static void notifyMatchEnded() {
        sRollbackSessionActive = false;
        MatchEndListener listener = sMatchEndListener;
        if (listener != null) {
            listener.onRollbackMatchEnded();
        }
    }

    public static void reset() {
        sReadyLatch = new CountDownLatch(1);
        sFailureReason.set(null);
        sRollbackSessionActive = false;
        sMatchEndListener = null;
    }
}
