package ul.fcul.lasige.find.lib.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Created by hugonicolau on 03/11/2015.
 *
 * Utility class that persistently stores/retrieves FIND client's tokens, such as API token,
 * protocols tokens, and timestamp of last received packet.
 *
 */
public class TokenStore {
    // name of preferences file
    private static final String PREFERENCE_FILE = "FindPreferences";
    // name of API token preference
    private static final String KEY_APP_TOKEN = "api_key";
    // name of time stamp of last received packet
    private static final String KEY_LAST_PACKET_RECEIVED = "last_packet_received";

    // utility set that holds all keys that are not protocols
    private static final Set<String> NON_PROTOCOL_KEYS = new HashSet<>();
    static {
        NON_PROTOCOL_KEYS.add(KEY_APP_TOKEN);
        NON_PROTOCOL_KEYS.add(KEY_LAST_PACKET_RECEIVED);
    }

    // constructor - private to prevent instantiation
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

    /**
     * Utility method that stores a pair key-value.
     *
     * @param context Context of client app.
     * @param key Key name.
     * @param value Value associated to given key.
     */
    private static void saveKeyValue(Context context, String key, String value) {
        // get object
        final SharedPreferences.Editor editor = getSharedPreferences(context).edit();
        // put/replace key-value pair
        editor.putString(key, value);
        // save
        editor.apply();
    }

    /*
     * API KEY - UTILITY METHODS
     */

    /**
     * Stores an API key persistently.
     *
     * @param context Context of client app.
     * @param newApiKey API token.
     */
    public static void saveApiKey(Context context, String newApiKey) {
        saveKeyValue(context, KEY_APP_TOKEN, newApiKey);
    }

    /**
     * Retrieves previously saved API key.
     * @param context Context of client app.
     * @return API key value if it exists, or null if it does not exists.
     */
    public static String getApiKey(Context context) {
        final SharedPreferences prefs = getSharedPreferences(context);
        return prefs.getString(KEY_APP_TOKEN, null);
    }

    /*
     * PROTOCOL TOKENS - UTILITY METHODS
     */

    /**
     * Stores a protocol token and name persistently.
     *
     * @param context Context of client app.
     * @param protocolName Protocol name.
     * @param protocolToken Protocol token for that name.
     */
    public static void saveProtocolToken(Context context, String protocolName, String protocolToken) {
        saveKeyValue(context, protocolName, protocolToken);
    }

    /**
     * Retrieves all previously saved protocols tokens.
     *
     * @param context Context of client app.
     * @return Map of all protocol name-token.
     */
    public static HashMap<String, String> getRegisteredProtocolsWithTokens(Context context) {
        // get preferences object
        final SharedPreferences prefs = getSharedPreferences(context);

        // create new map that will be returned
        final HashMap<String, String> registeredProtocols = new HashMap<String, String>();
        // for all entries in preferences set
        for (final Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            // if key is a protocol name; that is, not an API key or timestamp of last packet
            if (!NON_PROTOCOL_KEYS.contains(entry.getKey())) {
                // then add key-value to new map
                registeredProtocols.put(entry.getKey(), (String) entry.getValue());
            }
        }
        // return all registered protocols
        return registeredProtocols;
    }

    /*
     * LAST RECEIVED PACKET
     */

    /**
     * Stores the timestamp for the last received packet.
     *
     * @param context Context of client app.
     * @param timestamp Timestamp of last received packet.
     */
    public static void saveLastPacketReceived(Context context, long timestamp) {
        final SharedPreferences.Editor editor = getSharedPreferences(context).edit();
        editor.putLong(KEY_LAST_PACKET_RECEIVED, timestamp);
        editor.apply();
    }

    /**
     * Retrieves the timestamp of previously stored packet.
     *
     * @param context Context of client app.
     * @return Timestamp of last received packet.
     */
    public static long getLastPacketReceived(Context context) {
        final SharedPreferences prefs = getSharedPreferences(context);
        return prefs.getLong(KEY_LAST_PACKET_RECEIVED, 0);
    }
}
