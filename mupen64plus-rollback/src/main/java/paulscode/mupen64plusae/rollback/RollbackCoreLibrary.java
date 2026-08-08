package paulscode.mupen64plusae.rollback;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * Minimal JNA binding for libmupen64plus-core.so, scoped to what
 * RollbackCoreBridge actually needs (M64CMD_ROLLBACK_* dispatch via
 * CoreDoCommand). This intentionally does NOT reuse
 * paulscode.android.mupen64plusae.jni.CoreLibrary: that class lives in the
 * app module, and the app module depends on this (rollback) module, not
 * the other way around, so importing it here would be a circular module
 * dependency that fails to compile.
 *
 * This binds to the exact same libmupen64plus-core.so the rest of the app
 * already loads (see RollbackCoreBridge.init()) - it is just a second,
 * independently-declared JNA view of the same native library, not a
 * second copy of the library itself.
 */
@SuppressWarnings({"unused", "UnusedReturnValue"})
public interface RollbackCoreLibrary extends Library {

    /* CoreDoCommand()
     *
     * This function sends a command to the emulator core. Used here for
     * the M64CMD_ROLLBACK_* command family only.
     */
    int CoreDoCommand(int Command, int ParamInt, Pointer ParamPtr);
}
