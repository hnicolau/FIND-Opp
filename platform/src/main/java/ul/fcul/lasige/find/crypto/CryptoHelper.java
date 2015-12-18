package ul.fcul.lasige.find.crypto;

import android.content.Context;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import org.abstractj.kalium.SodiumJNI;
import org.abstractj.kalium.crypto.Hash;
import org.abstractj.kalium.crypto.Random;
import org.abstractj.kalium.encoders.Encoder;
import org.abstractj.kalium.keys.KeyPair;
import org.abstractj.kalium.keys.PrivateKey;
import org.abstractj.kalium.keys.SigningKey;
import org.abstractj.kalium.keys.VerifyKey;

import static com.google.common.base.Preconditions.checkArgument;
import static org.abstractj.kalium.SodiumConstants.BOXZERO_BYTES;
import static org.abstractj.kalium.SodiumConstants.NONCE_BYTES;
import static org.abstractj.kalium.SodiumConstants.PUBLICKEY_BYTES;
import static org.abstractj.kalium.SodiumConstants.ZERO_BYTES;
import static org.abstractj.kalium.crypto.Util.isValid;
import static org.abstractj.kalium.crypto.Util.slice;

import ul.fcul.lasige.find.data.ConfigurationStore;
import ul.fcul.lasige.find.utils.ByteUtils;

/**
 * Helper class to encrypt/decrypt and sign data.
 *
 * Created by hugonicolau on 05/11/2015.
 */
public class CryptoHelper {

    private static final int BOX_MACBYTES = ZERO_BYTES - BOXZERO_BYTES;

    /**
     * Checks whether the native libraries are working correctly.
     * @return true if native libraries are working correctly, false otherwise.
     */
    public static boolean testNativeLibrary() {
        // create new Ed25519 private key
        final SigningKey edSecretKey = new SigningKey();

        // transform to corresponding Curve25519 private key
        final byte[] digest = new Hash().sha512(slice(edSecretKey.toBytes(), 0, 32));
        digest[0] &= 248;
        digest[31] &= 127;
        digest[31] |= 64;
        final PrivateKey curveSecretKey = new PrivateKey(slice(digest, 0, 32));

        // create public keys in different ways and compare the results
        final byte[] originalCurvePublicKey =
                new KeyPair(curveSecretKey.toBytes()).getPublicKey().toBytes();
        final byte[] convertedCurvePublicKey =
                CryptoHelper.convertEdPublicKeyToCurve(edSecretKey.getVerifyKey().toBytes());
        return Arrays.equals(originalCurvePublicKey, convertedCurvePublicKey);
    }

    /**
     * Converts public key to curve. Throws {@link RuntimeException} in case of error.
     * @param publicEdKey Public key
     * @return Curve key.
     */
    protected static byte[] convertEdPublicKeyToCurve(byte[] publicEdKey) {
        checkArgument(publicEdKey != null && publicEdKey.length == PUBLICKEY_BYTES,
                "Invalid format for public key");

        final byte[] publicCurveKey = new byte[PUBLICKEY_BYTES];
        isValid(SodiumJNI.crypto_sign_ed25519_convert_key(publicCurveKey, publicEdKey),
                String.format("Could not convert public key '%s' to Curve25519 format!",
                        Encoder.HEX.encode(publicEdKey)));
        return publicCurveKey;
    }

    /**
     * Encrypt data with receiver's public key. Throws {@link RuntimeException} in case of error.
     * @param context Application context.
     * @param plaintext Data.
     * @param receiverPublicKey Receiver's public key.
     * @return Ciphered data.
     */
    public static byte[] encrypt(Context context, byte[] plaintext, byte[] receiverPublicKey) {
        // convert receiver's public key to Curve25519 format
        final byte[] receiverPublicCurveKey = convertEdPublicKeyToCurve(receiverPublicKey);

        // get own private Curve25519 key
        PrivateKey senderSecretKey = ConfigurationStore.getMasterEncryptionKey(context);

        // encrypt message inside NaCl box
        final int ciphertextLength = plaintext.length + BOX_MACBYTES;
        final byte[] ciphertext = new byte[ciphertextLength + NONCE_BYTES];
        final byte[] nonce = new Random().randomBytes(NONCE_BYTES);
        isValid(SodiumJNI.crypto_box_easy(ciphertext, plaintext, plaintext.length, nonce,
                receiverPublicCurveKey, senderSecretKey.toBytes()), "Encryption failed");

        // append nonce to ciphertext and return it
        System.arraycopy(nonce, 0, ciphertext, ciphertextLength, NONCE_BYTES);
        return ciphertext;
    }

    /**
     * Decrypts data with sender's public key. Throws {@link RuntimeException} in case of error.
     * @param context Application context.
     * @param ciphertextAndNonce Ciphered text with nonce.
     * @param senderPublicKey Sender's public key
     * @return Deciphered data.
     */
    public static byte[] decrypt(Context context, byte[] ciphertextAndNonce, byte[] senderPublicKey) {
        // convert sender's public key to Curve25519 format
        final byte[] senderPublicCurveKey = convertEdPublicKeyToCurve(senderPublicKey);

        // get own private Curve25519 key
        PrivateKey receiverSecretKey = ConfigurationStore.getMasterEncryptionKey(context);

        // decrypt message from NaCl box
        final byte[] ciphertext = Arrays.copyOf(ciphertextAndNonce, ciphertextAndNonce.length - NONCE_BYTES);
        final byte[] nonce = Arrays.copyOfRange(ciphertextAndNonce, ciphertext.length, ciphertextAndNonce.length);
        final byte[] plaintext = new byte[ciphertext.length - BOX_MACBYTES];

        isValid(SodiumJNI.crypto_box_open_easy(plaintext, ciphertext, ciphertext.length, nonce,
                senderPublicCurveKey, receiverSecretKey.toBytes()), "Decryption failed");

        return plaintext;
    }

    /**
     * Sign data with platform's signing key.
     * @param context Application context.
     * @param data Data.
     * @return Signed data.
     */
    public static byte[] sign(Context context, byte[] data) {
        final SigningKey signingKey = ConfigurationStore.getMasterSigningKey(context);
        return signingKey.sign(data);
    }

    /**
     * Verify data with sender's signature and public key.
     * @param data Data.
     * @param signature Sender's signature.
     * @param senderPublicKey Sender's public key.
     * @return true if it is valid, false otherwise.
     */
    public static boolean verify(byte[] data, byte[] signature, byte[] senderPublicKey) {
        final VerifyKey verifyKey = new VerifyKey(senderPublicKey);
        return verifyKey.verify(data, signature);
    }

    /**
     * Creates a hash value from data using a one-way hash function.
     * @param data Data.
     * @return Hash value.
     */
    public static byte[] createDigest(byte[] data) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            // SHA1 is expected to be available
            throw new UnsupportedOperationException("SHA1 digest function not present on this device.");
        }
        md.update(data);
        return md.digest();
    }

    /**
     * Creates a hash value from data (in hexadecimal) using a one-way hash function.
     * @param data Data
     * @return Hash value as hexadecimal.
     */
    public static String createHexDigest(byte[] data) {
        final byte[] digest = createDigest(data);
        return ByteUtils.bytesToHex(digest);
    }
}
