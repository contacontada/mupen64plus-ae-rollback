package paulscode.mupen64plusae.rollback;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/** Minimal JNA view of the native core API required by rollback. */
public interface RollbackCoreLibrary extends Library {
    int CoreDoCommand(int command, int paramInt, Pointer paramPtr);
}
