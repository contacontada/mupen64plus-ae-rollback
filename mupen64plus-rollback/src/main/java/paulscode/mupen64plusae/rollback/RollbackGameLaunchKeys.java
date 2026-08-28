package paulscode.mupen64plusae.rollback;

/**
 * Intent extra keys for launching GameActivity in rollback mode.
 *
 * These intentionally do NOT reuse ActivityHelper.Keys.* (which builds
 * each key as Keys.class.getCanonicalName() + "FIELD_NAME" via
 * reflection). In testing, every extra written on the
 * RollbackNetplayService side using ActivityHelper.Keys.* came back
 * null/false on the GameActivity side despite being set - see
 * rollback_debug.log evidence from 2026-08-19. Rather than chase the
 * exact reason inside a mechanism this feature doesn't need to depend
 * on, this defines its own small, fixed set of plain string keys that
 * both the writer (RollbackNetplayService) and reader (GameActivity)
 * reference identically, removing that indirection entirely for the
 * rollback launch path specifically. The app's normal (non-rollback)
 * game launch path is untouched and keeps using ActivityHelper.Keys as
 * before.
 */
public final class RollbackGameLaunchKeys {

    private RollbackGameLaunchKeys() { }

    public static final String ROM_PATH = "paulscode.mupen64plusae.rollback.ROM_PATH";
    public static final String ZIP_PATH = "paulscode.mupen64plusae.rollback.ZIP_PATH";
    public static final String ROM_MD5 = "paulscode.mupen64plusae.rollback.ROM_MD5";
    public static final String ROM_CRC = "paulscode.mupen64plusae.rollback.ROM_CRC";
    public static final String ROM_HEADER_NAME = "paulscode.mupen64plusae.rollback.ROM_HEADER_NAME";
    public static final String ROM_COUNTRY_CODE = "paulscode.mupen64plusae.rollback.ROM_COUNTRY_CODE";
    public static final String ROM_ART_PATH = "paulscode.mupen64plusae.rollback.ROM_ART_PATH";
    public static final String ROM_GOOD_NAME = "paulscode.mupen64plusae.rollback.ROM_GOOD_NAME";
    public static final String ROM_DISPLAY_NAME = "paulscode.mupen64plusae.rollback.ROM_DISPLAY_NAME";
    public static final String ROLLBACK_MODE = "paulscode.mupen64plusae.rollback.ROLLBACK_MODE";

    // Which N64 controller ports the match needs plugged in, and which
    // one is this device's own. Neither was previously passed to
    // GameActivity, so GamePrefs fell back to whatever this specific
    // device's local (offline/single-player) controller settings
    // happened to be - normally just port 1 - leaving player 2's port
    // unplugged in the emulated game regardless of what the network
    // session agreed. See GamePrefs' rollbackForcedPlayerCount parameter.
    public static final String NUM_PLAYERS = "paulscode.mupen64plusae.rollback.NUM_PLAYERS";
    public static final String LOCAL_PLAYER = "paulscode.mupen64plusae.rollback.LOCAL_PLAYER";
}
