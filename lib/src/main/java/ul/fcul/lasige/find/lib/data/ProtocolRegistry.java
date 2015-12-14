package ul.fcul.lasige.find.lib.data;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Set;

/**
 * Created by hugonicolau on 03/11/2015.
 *
 * This class stores the mapping of protocol name - protocol token. It implements the Singleton
 * design pattern.
 */
public class ProtocolRegistry {
    private static final String TAG = ProtocolRegistry.class.getSimpleName();

    // singleton instance
    private static ProtocolRegistry sInstance;

    // context of client app
    private final Context mContext;

     // saches the mapping of protocolName - protocolToken.
    private final HashMap<String, String> mProtocolTokenCache;

    /**
     * Get an instance to ProtocolRegistry. Access is synchronized.
     *
     * @param context
     * @return
     */
    public static synchronized ProtocolRegistry getInstance(Context context) {
        if (sInstance == null) { sInstance = new ProtocolRegistry(context); }
        return sInstance;
    }

    // constructor
    private ProtocolRegistry(Context context) {
        // set context
        mContext = context;
        // retrieves all presistently stored protocols
        mProtocolTokenCache = TokenStore.getRegisteredProtocolsWithTokens(mContext);
    }

    /**
     * Checks whether a protocol is registered.
     *
     * @param protocolName Protocol name.
     * @return return true if protocol was previously registered, false otherwise.
     */
    public boolean contains(String protocolName) {
        return mProtocolTokenCache.containsKey(protocolName);
    }

    /**
     * Stores the pair protocol name - token.
     *
     * @param protocolName Protocol's name.
     * @param protocolToken Protocol's token.
     */
    public void add(String protocolName, String protocolToken) {
        TokenStore.saveProtocolToken(mContext, protocolName, protocolToken);
        mProtocolTokenCache.put(protocolName, protocolToken);
    }

    /**
     * Get token for a previously stored protocol name.
     *
     * @param protocolName Protocol name.
     * @return Token associated with the protocol name or null if protocol name does not exist.
     */
    public String getToken(String protocolName) {
        return mProtocolTokenCache.get(protocolName);
    }

    // used then 3rd-party apps only register one protocol and enqueue messages without protocoltoken

    /**
     * Returns the token of the first stored protocol. This method should only be called when there
     * is a single protocol registered. In case there is more than one protocol or none at all, it
     * throws an IllegalStateException
     *
     * @return Token of first stored protocol
     */
    public String getSingleToken() {
        if (mProtocolTokenCache.size() != 1) {
            Log.e(TAG, "Ambigous call: there is " + (mProtocolTokenCache.size() > 1 ? "more than one" : "not even one") +
                " protocol registered");
            /*throw new IllegalStateException(
                    String.format("Ambigous call: There is %s protocol registered.",
                            mProtocolTokenCache.size() > 1 ? "more than one" : "not even one"));*/
            return null;
        }
        return (String) mProtocolTokenCache.values().toArray()[0];
    }

    /**
     * Retrieves a set with the mapping protocol name - protocol token.
     * @return Mapping of protocol name - protocol token.
     */
    public Set<String> getRegisteredProtocols() {
        return mProtocolTokenCache.keySet();
    }
}
