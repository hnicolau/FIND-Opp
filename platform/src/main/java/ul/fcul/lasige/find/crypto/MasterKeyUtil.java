package ul.fcul.lasige.find.crypto;

import android.content.Context;
import android.util.Log;

import org.abstractj.kalium.crypto.Hash;
import org.abstractj.kalium.keys.PrivateKey;
import org.abstractj.kalium.keys.SigningKey;
import ul.fcul.lasige.find.data.ConfigurationStore;
import ul.fcul.lasige.find.data.DbController;

import static org.abstractj.kalium.crypto.Util.slice;

/**
 * Utility class to create and store platform's signing/public (identity) and private keys.
 *
 * Created by hugonicolau on 05/11/2015.
 */
public class MasterKeyUtil {
    private static final String TAG = MasterKeyUtil.class.getSimpleName();

    /**
     * Creates and stores platform's signing/public (identity) and private keys.
     * @param context Application context.
     */
    public static void create(Context context) {
        // create new Ed25519 signing key
        final SigningKey edSecretKey = new SigningKey();

        // transform to corresponding Curve25519 private key
        final byte[] digest = new Hash().sha512(slice(edSecretKey.toBytes(), 0, 32));
        digest[0] &= 248;
        digest[31] &= 127;
        digest[31] |= 64;
        // and create private key
        final PrivateKey curveSecretKey = new PrivateKey(slice(digest, 0, 32));

        // save keys in store
        if (ConfigurationStore.saveMasterKeys(context, edSecretKey, curveSecretKey)) {
            // update identity in database
            Log.d(TAG, "Created new identity: " + edSecretKey.getVerifyKey());
            new DbController(context).insertIdentity("master", edSecretKey.getVerifyKey().toBytes(), null);
        }
    }
}
