/*
 * Shared intent extra keys used by the rollback netplay service to launch
 * the main game Activity. The string values MUST match the keys declared
 * in {@code paulscode.android.mupen64plusae.ActivityHelper.Keys} inside
 * the app module, since the app module cannot depend on this library at
 * compile time (circular dependency).
 *
 * GameActivity reads these extras by name, so only the literal string
 * values matter at runtime.
 */
package paulscode.mupen64plusae.rollback;

public final class RollbackRomKeys {
    private static final String NAMESPACE =
            "paulscode.android.mupen64plusae.ActivityHelper$Keys.";

    public static final String ROM_PATH        = NAMESPACE + "ROM_PATH";
    public static final String ZIP_PATH        = NAMESPACE + "ZIP_PATH";
    public static final String ROM_MD5         = NAMESPACE + "ROM_MD5";
    public static final String ROM_CRC         = NAMESPACE + "ROM_CRC";
    public static final String ROM_HEADER_NAME = NAMESPACE + "ROM_HEADER_NAME";
    public static final String ROM_COUNTRY_CODE= NAMESPACE + "ROM_COUNTRY_CODE";
    public static final String ROM_ART_PATH    = NAMESPACE + "ROM_ART_PATH";
    public static final String ROM_GOOD_NAME   = NAMESPACE + "ROM_GOOD_NAME";
    public static final String ROM_DISPLAY_NAME= NAMESPACE + "ROM_DISPLAY_NAME";
    public static final String DO_RESTART      = NAMESPACE + "DO_RESTART";
    public static final String ROLLBACK_MODE   = NAMESPACE + "ROLLBACK_MODE";

    private RollbackRomKeys() {}
}
