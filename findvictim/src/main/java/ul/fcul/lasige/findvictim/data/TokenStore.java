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



    // SENSOR SERVICE STATE
    public static SensorsService.SensorsState getSensorsState(Context context) {
        SharedPreferences pref = getSharedPreferences(context);
        final String state = pref.getString(KEY_SENSOR_STATE, null);
        return (state == null ? SensorsService.DEFAULT_STATE: SensorsService.SensorsState.valueOf(state));
    }

    public static void saveSensorsState(Context context, SensorsService.SensorsState state) {
        getSharedPreferences(context).edit().putString(KEY_SENSOR_STATE, state.name()).apply();
    }


}
