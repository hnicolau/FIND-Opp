package ul.fcul.lasige.find.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.abstractj.kalium.encoders.Encoder;
import org.abstractj.kalium.keys.PrivateKey;
import org.abstractj.kalium.keys.SigningKey;

import ul.fcul.lasige.find.beaconing.Policy;
import ul.fcul.lasige.find.service.SupervisorService;

/**
 * Utility class that stores and retrieves persistent values that are crucial to restore platform's
 * state.
 *
 * Created by hugonicolau on 04/11/2015.
 */
public class ConfigurationStore {
    private static final String TAG = ConfigurationStore.class.getSimpleName();

    // shared preferences file name
    private static final String PREFERENCE_FILE = "FINDPreferences";

    // key that indicates whether it is the first time we are running the platform
    private static final String KEY_FIRST_RUN = "first_run";
    // key that holds platform's signing key
    private static final String KEY_MASTER_SIGNING_KEY = "master_signing_key";
    // key that holds platform's encryption key
    private static final String KEY_MASTER_ENCRYPTION_KEY = "master_encryption_key";
    // current platform (Supervisor) state
    private static final String KEY_SUPERVISOR_STATE = "supervisor_state";
    // current policy
    private static final String KEY_CURRENT_POLICY = "current_policy";

    /**
     * Constructor. It is private to prevent instantiation. All class' methods are static.
     */
    private ConfigurationStore() {
        // prevent instantiation
    }

    /**
     * Returns application's {@link SharedPreferences} object.
     * @param context Application context.
     * @return SharedPreferences object.
     * @see SharedPreferences
     */
    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE);
    }

    /**
     * Returns whether it is the first time the platform is running. Calling this method the second
     * time will return false.
     * @param context Application context.
     * @return true if it is the first time we call the method, false otherwise.
     */
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
    /**
     * Returns platform's {@link SigningKey} object.
     * @param context Application context.
     * @return {@link SigningKey} object.
     * @see SigningKey
     */
    public static SigningKey getMasterSigningKey(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        final String hexKey = config.getString(KEY_MASTER_SIGNING_KEY, null);
        try {
            return new SigningKey(hexKey, Encoder.HEX);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Returns platform's encryption key.
     * @param context Application context.
     * @return {@link PrivateKey} object.
     * @see PrivateKey
     */
    public static PrivateKey getMasterEncryptionKey(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        final String hexKey = config.getString(KEY_MASTER_ENCRYPTION_KEY, null);
        try {
            return new PrivateKey(hexKey);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Stores platform's signing key and encryption key.
     * @param context Application context.
     * @param edSecretKey Signing key.
     * @param curveSecretKey Encryption key.
     * @return true if neither key was previously stores, false otherwise.
     * @see SigningKey
     * @see PrivateKey
     */
    public static synchronized boolean saveMasterKeys(Context context, SigningKey edSecretKey, PrivateKey curveSecretKey) {
        final SharedPreferences config = getSharedPreferences(context);
        // if either exists, return false
        if (config.contains(KEY_MASTER_SIGNING_KEY) || config.contains(KEY_MASTER_ENCRYPTION_KEY)) {
            return false;
        }

        // put keys in the sharedpreferences file
        config.edit()
                .putString(KEY_MASTER_SIGNING_KEY, edSecretKey.toString())
                .putString(KEY_MASTER_ENCRYPTION_KEY, curveSecretKey.toString())
                .apply();
        return true;
    }

    // SUPERVISOR STATE

    /**
     * Retrives previously stored {@link ul.fcul.lasige.find.service.SupervisorService.SupervisorState SupervisorState}
     * @param context Application context
     * @return {@link ul.fcul.lasige.find.service.SupervisorService.SupervisorState SupervisorState}; In case there is no
     * previously stored state, then returns {@link ul.fcul.lasige.find.service.SupervisorService.SupervisorState#DEFAULT_STATE DEFAULT_STATE}
     * @see ul.fcul.lasige.find.service.SupervisorService.SupervisorState
     */
    public static SupervisorService.SupervisorState getSupervisorState(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        final String state = config.getString(KEY_SUPERVISOR_STATE, null);
        return (state == null ? SupervisorService.DEFAULT_STATE : SupervisorService.SupervisorState.valueOf(state));
    }

    /**
     * Stores {@link ul.fcul.lasige.find.service.SupervisorService.SupervisorState}.
     * @param context Application context.
     * @param state {@link ul.fcul.lasige.find.service.SupervisorService.SupervisorState} object.
     */
    public static void saveSupervisorState(Context context, SupervisorService.SupervisorState state) {
        getSharedPreferences(context)
                .edit()
                .putString(KEY_SUPERVISOR_STATE, state.name())
                .apply();
    }

    // CURRENT POLICY
    /**
     * Retrives previously stored {@link Policy}.
     * @param context Application context.
     * @return {@link Policy}; if there is no previously stored policy, then returns {@link Policy#DEFAULT_POLICY}.
     */
    public static Policy getCurrentPolicy(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        final String currentPolicy = config.getString(KEY_CURRENT_POLICY, null);
        return (currentPolicy == null ? Policy.DEFAULT_POLICY : Policy.valueOf(currentPolicy));
    }

    /**
     * Stores {@link Policy}
     * @param context Application context.
     * @param policy {@link Policy} object.
     */
    public static void saveCurrentPolicy(Context context, Policy policy) {
        getSharedPreferences(context)
                .edit()
                .putString(KEY_CURRENT_POLICY, policy.name())
                .apply();
    }
}
