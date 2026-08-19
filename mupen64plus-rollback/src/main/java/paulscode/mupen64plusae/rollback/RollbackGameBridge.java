package paulscode.mupen64plusae.rollback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges GameActivity (owns ROM loading / the real emulation core, runs
 * in the separate ":EmulationProcess" - see AndroidManifest.xml) and
 * RollbackNetplayService (drives an in-progress rollback session on a
 * background thread, runs in the app's default process).
 *
 * IMPORTANT: GameActivity and RollbackNetplayService run in DIFFERENT
 * OS processes. Static fields/CountDownLatches are per-process - a
 * previous version of this class relied on static state and silently
 * never worked, because each process had its own disconnected copy.
 * This version uses Android broadcasts (which the system relays across
 * process boundaries within the same app) instead.
 *
 * Flow:
 *   1. RollbackNetplayService.startGameForRollback() registers a receiver
 *      for CORE_READY/CORE_START_FAILED, launches GameActivity, then
 *      blocks waiting for one of those.
 *   2. GameActivity loads the ROM/starts the core exactly like a normal
 *      game launch, but (because ROLLBACK_MODE is set) pauses instead of
 *      auto-resuming once the core reports started, then broadcasts
 *      CORE_READY (or CORE_START_FAILED with a reason, if something
 *      failed before that point).
 *   3. RollbackNetplayService's wait unblocks, and it proceeds to
 *      configure and run the rollback session.
 *   4. When the match ends, RollbackNetplayService broadcasts
 *      MATCH_ENDED so GameActivity (which registered its own receiver
 *      for it in onCreate) can close.
 */
public final class RollbackGameBridge {

    private static final String TAG = "RollbackGameBridge";

    private static final String ACTION_CORE_READY =
        "paulscode.mupen64plusae.rollback.ACTION_CORE_READY";
    private static final String ACTION_CORE_START_FAILED =
        "paulscode.mupen64plusae.rollback.ACTION_CORE_START_FAILED";
    private static final String ACTION_MATCH_ENDED =
        "paulscode.mupen64plusae.rollback.ACTION_MATCH_ENDED";
    private static final String EXTRA_REASON = "reason";

    // Local (same-process) bookkeeping only - safe as static state since
    // both readers/writers of this one are always RollbackNetplayService,
    // never GameActivity.
    private static volatile boolean sRollbackSessionActive = false;

    private RollbackGameBridge() { }

    // ---- Called by GameActivity (in :EmulationProcess) ----

    /** Broadcasts that the core is loaded, paused, and ready for rollback. */
    public static void notifyCoreReady(Context context) {
        Log.i(TAG, "Broadcasting core ready");
        RollbackDebugLog.log(context, TAG, "Sending broadcast: " + ACTION_CORE_READY);
        Intent intent = new Intent(ACTION_CORE_READY);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    /** Broadcasts that ROM loading / core startup itself failed. */
    public static void notifyCoreStartFailed(Context context, String reason) {
        Log.e(TAG, "Broadcasting core start failed: " + reason);
        Intent intent = new Intent(ACTION_CORE_START_FAILED);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_REASON, reason != null ? reason : "unknown error");
        context.sendBroadcast(intent);
    }

    /**
     * Registers a receiver (in GameActivity's process) that calls
     * onMatchEnded when RollbackNetplayService broadcasts that the match
     * is over. Returns the receiver so the caller can unregister it
     * (e.g. in onDestroy) - failing to unregister leaks the receiver.
     */
    public static BroadcastReceiver registerMatchEndReceiver(Context context, Runnable onMatchEnded) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                onMatchEnded.run();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_MATCH_ENDED);
        registerReceiverCompat(context, receiver, filter);
        return receiver;
    }

    // ---- Called by RollbackNetplayService (default process) ----

    /**
     * Registers a receiver for CORE_READY/CORE_START_FAILED. Call this
     * BEFORE starting GameActivity (not after) - a very fast failure in
     * GameActivity could otherwise broadcast before anyone is listening.
     * Pass the returned handle to awaitCoreReady().
     */
    public static Object beginWaitForCoreReady(Context context) {
        sRollbackSessionActive = true;
        RollbackDebugLog.log(context, TAG, "beginWaitForCoreReady() registering receiver");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> failureReason = new AtomicReference<>(null);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                RollbackDebugLog.log(ctx, TAG, "Received broadcast: " + intent.getAction());
                if (ACTION_CORE_START_FAILED.equals(intent.getAction())) {
                    failureReason.set(intent.getStringExtra(EXTRA_REASON));
                }
                latch.countDown();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CORE_READY);
        filter.addAction(ACTION_CORE_START_FAILED);
        registerReceiverCompat(context, receiver, filter);

        return new CoreReadyWait(context, receiver, latch, failureReason);
    }

    /**
     * Blocks the calling thread (must NOT be the main/UI thread) until
     * the wait registered via beginWaitForCoreReady() resolves, or the
     * timeout elapses.
     *
     * @return null on success, or a human-readable failure reason.
     */
    public static String awaitCoreReady(Object handle, long timeoutMs) {
        CoreReadyWait wait = (CoreReadyWait) handle;
        try {
            boolean signaled = wait.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!signaled) {
                return "Timed out waiting for the game to start";
            }
            return wait.failureReason.get(); // null if CORE_READY was the one that fired
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for the game to start";
        } finally {
            try {
                wait.context.unregisterReceiver(wait.receiver);
            } catch (Exception e) { /* already unregistered / never registered */ }
        }
    }

    private static final class CoreReadyWait {
        final Context context;
        final BroadcastReceiver receiver;
        final CountDownLatch latch;
        final AtomicReference<String> failureReason;

        CoreReadyWait(Context context, BroadcastReceiver receiver, CountDownLatch latch,
                      AtomicReference<String> failureReason) {
            this.context = context;
            this.receiver = receiver;
            this.latch = latch;
            this.failureReason = failureReason;
        }
    }

    public static boolean isRollbackSessionActive() {
        return sRollbackSessionActive;
    }

    /** Called by RollbackNetplayService once the match/session is over. */
    public static void notifyMatchEnded(Context context) {
        sRollbackSessionActive = false;
        Log.i(TAG, "Broadcasting match ended");
        Intent intent = new Intent(ACTION_MATCH_ENDED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    public static void reset() {
        sRollbackSessionActive = false;
    }

    // ---- Helpers ----

    private static void registerReceiverCompat(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        // Android 13+ (API 33) requires RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED
        // to be specified explicitly for dynamically-registered receivers.
        // These broadcasts are internal to this app (explicit setPackage()
        // on the sending side), so NOT_EXPORTED is correct - no other app
        // should be able to trigger these.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }
}
