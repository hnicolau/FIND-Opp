package ul.fcul.lasige.findvictim.data;

import android.content.Context;
import android.content.SharedPreferences;

import ul.fcul.lasige.findvictim.sensors.SensorsService;

/**
 * Created by hugonicolau on 27/11/15.
 */
public class TokenStore {

    // file name
    private static final String PREFERENCE_FILE = "FindVictimPreferences";
    // sensor service
    public static final String KEY_SENSOR_STATE = "sensorState";
    public static final String KEY_SENSOR_IS_FIRST_LOCATION = "isFirstLocation";
    // registration
    private static final String KEY_SENT_TOKEN_TO_SERVER = "registrationSentTokenToServer";
    public static final String KEY_REGISTRATION_COMPLETE = "registrationComplete";
    private static final String KEY_REGISTRATION_LOCALE = "registrationLocale";
    private static final String KEY_REGISTRATION_MAC = "registrationMac";
    private static final String KEY_REGISTRATION_EMAIL = "registrationEmail";
    private static final String KEY_REGISTRATION_TOKEN = "registrationToken";

    private TokenStore() {}

    /**
     * Get Preferences object that holds all tokens.
     *
     * @param context Context of client app.
     * @return SharedPreferences object.
     *
     * @see SharedPreferences
     */
    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE);
    }

    // REGISTRATION IN GCM AND SERVER
    public static synchronized boolean isRegistered(Context context) {
        return getSharedPreferences(context).getBoolean(KEY_SENT_TOKEN_TO_SERVER, false);
    }

    public static synchronized void saveRegistration(Context context, String locale, String mac, String email, String token) {
        getSharedPreferences(context).edit().putString(KEY_REGISTRATION_LOCALE, locale).apply();
        getSharedPreferences(context).edit().putString(KEY_REGISTRATION_MAC, mac).apply();
        getSharedPreferences(context).edit().putString(KEY_REGISTRATION_EMAIL, email).apply();
        getSharedPreferences(context).edit().putString(KEY_REGISTRATION_TOKEN, token).apply();
        getSharedPreferences(context).edit().putBoolean(KEY_SENT_TOKEN_TO_SERVER, true).apply();
    }

    public static synchronized void deleteRegistration(Context context) {
        getSharedPreferences(context).edit().remove(KEY_REGISTRATION_LOCALE).apply();
        getSharedPreferences(context).edit().remove(KEY_REGISTRATION_MAC).apply();
        getSharedPreferences(context).edit().remove(KEY_REGISTRATION_EMAIL).apply();
        getSharedPreferences(context).edit().remove(KEY_REGISTRATION_TOKEN).apply();
        getSharedPreferences(context).edit().putBoolean(KEY_SENT_TOKEN_TO_SERVER, false).apply();
    }

    public static synchronized String getMacAddress(Context context) {
        return getSharedPreferences(context).getString(KEY_REGISTRATION_MAC, "unknown");
    }

    public static synchronized String getGoogleAccount(Context context) {
        return getSharedPreferences(context).getString(KEY_REGISTRATION_EMAIL, "unknown");
    }

    // SENSOR SERVICE STATE
    public static SensorsService.SensorsState getSensorsState(Context context) {
        SharedPreferences pref = getSharedPreferences(context);
        final String state = pref.getString(KEY_SENSOR_STATE, null);
        return (state == null ? SensorsService.DEFAULT_STATE: SensorsService.SensorsState.valueOf(state));
    }

    public static void saveSensorsState(Context context, SensorsService.SensorsState state) {
        getSharedPreferences(context).edit().putString(KEY_SENSOR_STATE, state.name()).apply();
    }

    public static boolean isFirstLocation(Context context) {
        return getSharedPreferences(context).getBoolean(KEY_SENSOR_IS_FIRST_LOCATION, true);
    }

    public static void setIsFirstLocation(Context context, boolean value) {
        getSharedPreferences(context).edit().putBoolean(KEY_SENSOR_IS_FIRST_LOCATION, value).apply();
    }
}
