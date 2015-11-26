package ul.fcul.lasige.find.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.abstractj.kalium.encoders.Encoder;
import org.abstractj.kalium.keys.PrivateKey;
import org.abstractj.kalium.keys.SigningKey;

import ul.fcul.lasige.find.beaconing.Policy;
import ul.fcul.lasige.find.service.SupervisorService;

/**
 * Created by hugonicolau on 04/11/2015.
 */
public class ConfigurationStore {
    private static final String TAG = ConfigurationStore.class.getSimpleName();

    private static final String PREFERENCE_FILE = "FINDPreferences";

    private static final String KEY_FIRST_RUN = "first_run";
    private static final String KEY_MASTER_SIGNING_KEY = "master_signing_key";
    private static final String KEY_MASTER_ENCRYPTION_KEY = "master_encryption_key";
    private static final String KEY_SUPERVISOR_STATE = "supervisor_state";
    private static final String KEY_CURRENT_POLICY = "current_policy";

    private ConfigurationStore() {
        // prevent instantiation
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE);
    }

    public static synchronized boolean isFirstRun(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        if (!config.contains(KEY_FIRST_RUN)) {
            final long currentTimeMillis = System.currentTimeMillis();
            config.edit().putLong(KEY_FIRST_RUN, currentTimeMillis).apply();
            return true;
        }
        return false;
    }

    // MASTER KEY
    public static SigningKey getMasterSigningKey(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        final String hexKey = config.getString(KEY_MASTER_SIGNING_KEY, null);
        try {
            return new SigningKey(hexKey, Encoder.HEX);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static PrivateKey getMasterEncryptionKey(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        final String hexKey = config.getString(KEY_MASTER_ENCRYPTION_KEY, null);
        try {
            return new PrivateKey(hexKey);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static synchronized boolean saveMasterKeys(Context context, SigningKey edSecretKey, PrivateKey curveSecretKey) {
        final SharedPreferences config = getSharedPreferences(context);
        if (config.contains(KEY_MASTER_SIGNING_KEY) || config.contains(KEY_MASTER_ENCRYPTION_KEY)) {
            return false;
        }

        config.edit()
                .putString(KEY_MASTER_SIGNING_KEY, edSecretKey.toString())
                .putString(KEY_MASTER_ENCRYPTION_KEY, curveSecretKey.toString())
                .apply();
        return true;
    }

    // SUPERVISOR STATE
    public static SupervisorService.SupervisorState getSupervisorState(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        final String state = config.getString(KEY_SUPERVISOR_STATE, null);
        return (state == null ? SupervisorService.DEFAULT_STATE : SupervisorService.SupervisorState.valueOf(state));
    }

    public static void saveSupervisorState(Context context, SupervisorService.SupervisorState state) {
        getSharedPreferences(context)
                .edit()
                .putString(KEY_SUPERVISOR_STATE, state.name())
                .apply();
    }

    // CURRENT POLICY
    public static Policy getCurrentPolicy(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        final String currentPolicy = config.getString(KEY_CURRENT_POLICY, null);
        return (currentPolicy == null ? Policy.DEFAULT_POLICY : Policy.valueOf(currentPolicy));
    }

    public static void saveCurrentPolicy(Context context, Policy policy) {
        getSharedPreferences(context)
                .edit()
                .putString(KEY_CURRENT_POLICY, policy.name())
                .apply();
    }
}
