package paulscode.android.mupen64plusae.jni;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * Minimal JNA view of the native core API required by rollback.
 * Kept local to avoid a dependency from the library module back to the app.
 */
public interface CoreLibrary extends Library {
    int CoreDoCommand(int command, int paramInt, Pointer paramPtr);
}
