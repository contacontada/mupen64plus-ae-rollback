package paulscode.mupen64plusae.rollback;

import com.sun.jna.Callback;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * JNA structures for mupen64plus-core rollback API.
 * These mirror the C structs in m64p_types.h.
 */
public class RollbackJnaTypes {

    // Command ordinals (must match m64p_types.h C enum)
    public static final int M64CMD_ROLLBACK_SAVE_STATE = 30;
    public static final int M64CMD_ROLLBACK_LOAD_STATE = 31;
    public static final int M64CMD_ROLLBACK_FREE_STATE = 32;
    public static final int M64CMD_ROLLBACK_SET_INPUT_CALLBACK = 33;
    public static final int M64CMD_ROLLBACK_SET_INPUT_PLAYERS = 34;
    public static final int M64CMD_ROLLBACK_SET_DETERMINISTIC = 35;
    public static final int M64CMD_ROLLBACK_SAMPLE_INPUT = 36;
    public static final int M64CMD_ROLLBACK_EXECUTE = 37;
    public static final int M64CMD_ROLLBACK_RUN_FRAME = 38;
    public static final int M64CMD_ROLLBACK_GET_RUN_FRAME_STATS = 39;
    public static final int M64CMD_ROLLBACK_SET_VERBOSE_STATS = 40;
    public static final int M64CMD_ROLLBACK_SET_TIMESYNC_SCALE = 41;
    public static final int M64CMD_FRAME_OUTPUT_SET = 42;

    // Error codes
    public static final int M64ERR_SUCCESS = 0;

    /**
     * Callback: int begin_frame(void* user_data)
     */
    public interface BeginFrameCallback extends Callback {
        int invoke(Pointer userData);
    }

    /**
     * Callback: int end_frame(void* user_data)
     */
    public interface EndFrameCallback extends Callback {
        int invoke(Pointer userData);
    }

    /**
     * Callback: void pace_before_present(void* user_data)
     */
    public interface PaceBeforePresentCallback extends Callback {
        void invoke(Pointer userData);
    }

    /**
     * m64p_rollback_execute_callbacks struct
     */
    public static class RollbackExecuteCallbacks extends Structure {
        public Pointer user_data;
        public BeginFrameCallback begin_frame;
        public EndFrameCallback end_frame;
        public PaceBeforePresentCallback pace_before_present;
        public int pacing_trace_enabled;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("user_data", "begin_frame", "end_frame",
                "pace_before_present", "pacing_trace_enabled");
        }
    }

    /**
     * m64p_rollback_input_sample struct
     */
    public static class RollbackInputSample extends Structure {
        public Pointer values;
        public int size;
        public int players;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("values", "size", "players");
        }
    }
}
