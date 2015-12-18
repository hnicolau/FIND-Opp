package ul.fcul.lasige.find.data;

import android.database.Cursor;

import com.google.common.base.Objects;
import com.google.common.io.BaseEncoding;

/**
 *
 * This class represents the mapping between a client application and a protocol with a unique
 * access token
 *
 * Created by hugonicolau on 05/11/2015.
 */

public class ClientImplementation {
    private final static BaseEncoding HEX_CODER = BaseEncoding.base16();

    public static final int DEFAULT_TTL = 60 * 60 * 24; // 1 day

    private final String mToken;

    // app specific fields
    private final String mPackageName;

    // protocol specific fields
    private final byte[] mProtocolHash;
    private final String mProtocolName;
    private final boolean mIsEncrypted;
    private final boolean mIsSigned;
    private final int mDefaultTtl;

    // identity (platform) specific fields
    private final byte[] mIdentity; // public key
    private final String mDisplayName;

    private ClientImplementation(String token, String packageName, String protocolName,
                                 byte[] protocolHash, boolean isEncrypted, boolean isSigned,
                                 Integer defaultTtl, byte[] identity, String displayName) {
        mToken = token;

        mPackageName = packageName;

        mProtocolHash = protocolHash;
        mProtocolName = protocolName;
        mIsEncrypted = isEncrypted;
        mIsSigned = isSigned;
        mDefaultTtl = defaultTtl;

        mIdentity = identity;
        mDisplayName = displayName;
    }

    /**
     * Utility method to create a {@link ClientImplementation} object from a data cursor.
     * @param cursor Data cursor.
     * @return {@link ClientImplementation} object.
     */
    public static ClientImplementation fromCursor(Cursor cursor) {
        final String token = cursor.getString(
                cursor.getColumnIndexOrThrow(FullContract.ClientImplementations.COLUMN_TOKEN));
        final String packageName = cursor.getString(
                cursor.getColumnIndexOrThrow(FullContract.Apps.COLUMN_PACKAGE_NAME));
        final String protocolName = cursor.getString(
                cursor.getColumnIndexOrThrow(FullContract.Protocols.COLUMN_IDENTIFIER));
        final byte[] protocolHash = cursor.getBlob(
                cursor.getColumnIndexOrThrow(FullContract.Protocols.COLUMN_IDENTIFIER_HASH));
        final int encrypted = cursor.getInt(
                cursor.getColumnIndexOrThrow(FullContract.Protocols.COLUMN_ENCRYPTED));
        final int signed = cursor.getInt(
                cursor.getColumnIndexOrThrow(FullContract.Protocols.COLUMN_SIGNED));
        final byte[] identity = cursor.getBlob(
                cursor.getColumnIndexOrThrow(FullContract.Identities.COLUMN_PUBLICKEY));
        final String displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(FullContract.Identities.COLUMN_DISPLAY_NAME));

        Integer ttl = null;
        if (!cursor.isNull(cursor.getColumnIndexOrThrow(FullContract.Protocols.COLUMN_DEFAULT_TTL))) {
            ttl = cursor.getInt(cursor.getColumnIndex(FullContract.Protocols.COLUMN_DEFAULT_TTL));
        }

        return new ClientImplementation(token, packageName, protocolName,
                protocolHash, encrypted > 0, signed > 0, ttl, identity, displayName);
    }

    /**
     * Returns {@link ClientImplementation}'s token.
     * @return Token.
     */
    public String getToken() {
        return mToken;
    }

    /**
     * Returns client application's name.
     * @return App's name.
     */
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns protocol's name.
     * @return Protocol's name.
     */
    public String getProtocolName() {
        return mProtocolName;
    }

    /**
     * Returns protocol's hash value.
     * @return Protocol's hash value.
     */
    public byte[] getProtocolHash() {
        return mProtocolHash;
    }

    /**
     * Returns protocol's hash value as hexadecimal.
     * @return Protocol's hash value.
     */
    public String getProtocolHashAsHex() {
        return HEX_CODER.encode(mProtocolHash);
    }

    /**
     * Returns whether protocol's messages are encrypted.
     * @return true if it is encrypted, false otherwise.
     */
    public boolean isEncrypted() {
        return mIsEncrypted;
    }

    /**
     * Returns whether protocol's messages are signed (i.e. authenticated).
     * @return true if it is signed, false otherwise.
     */
    public boolean isSigned() {
        return mIsSigned;
    }

    /**
     * Returns protocol's messages time to live (in minutes).
     * @return Message time to live (TTL)
     */
    public int getDefaultTtl() {
        Integer ttl = mDefaultTtl;
        if (ttl == 0) { // original was == null, but that never happens
            ttl = DEFAULT_TTL;
        }
        return ttl;
    }

    /**
     * Returns platform (node) identity (public key).
     * @return Platform identity.
     */
    public byte[] getIdentity() {
        return mIdentity;
    }

    /**
     * Returns platform display name.
     * @return Platform's display name.
     */
    public String getDisplayName() {
        return mDisplayName;
    }

    /**
     * Returns {@link ClientImplementation}'s hashcode based on client app's and protocol's name.
     * @return Hash code.
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(mPackageName, mProtocolName);
    }
}
