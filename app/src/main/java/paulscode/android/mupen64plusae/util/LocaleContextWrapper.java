/*
 * Code taken from http://stackoverflow.com/questions/40221711/android-context-getresources-updateconfiguration-deprecated/40704077#40704077
 */

package paulscode.android.mupen64plusae.util;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;

import java.util.Locale;

public class LocaleContextWrapper extends ContextWrapper {

    private static String mLocaleCode = null;

    public LocaleContextWrapper(Context base) {
        super(base);
    }

    public static ContextWrapper wrap(Context context, String language) {
        Configuration config = context.getResources().getConfiguration();

        Locale locale = Locale.forLanguageTag(language);
        Locale.setDefault(locale);
        setSystemLocale(config, locale);

        context = context.createConfigurationContext(config);

        return new LocaleContextWrapper(context);
    }

    public static Locale getSystemLocale(Configuration config){
        return config.getLocales().get(0);
    }

    public static void setSystemLocale(Configuration config, Locale locale){
        config.setLocale(locale);
    }

    public static void setLocaleCode(String localeCode)
    {
        mLocaleCode = localeCode;
    }

    public static String getLocalCode()
    {
        return mLocaleCode;
    }
}