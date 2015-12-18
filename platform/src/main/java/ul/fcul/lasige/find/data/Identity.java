package ul.fcul.lasige.find.data;

import android.database.Cursor;

/**
 * Stores the public key of the FIND platform.
 *
 * Created by hugonicolau on 05/11/2015.
 */
public class Identity {
    // platform's public key
    private final byte[] mPublicKey;
    // platform's display name
    private final String mDisplayName;

    /**
     * Utility method to create an {@link Identity} object from a data cursor
     * @param cursor Data cursor.
     * @return {@link Identity} object.
     * @see Cursor
     */
    public static Identity fromCursor(Cursor cursor) {
        final byte[] publicKey = cursor.getBlob(cursor.getColumnIndex(FullContract.Identities.COLUMN_PUBLICKEY));
        final String displayName = cursor.getString(cursor.getColumnIndex(FullContract.Identities.COLUMN_DISPLAY_NAME));
        return new Identity(publicKey, displayName);
    }

    /**
     * Constructor
     * @param publicKey Platform's public key.
     * @param displayName Platform's display name.
     */
    private Identity(byte[] publicKey, String displayName) {
        mPublicKey = publicKey;
        mDisplayName = displayName;
    }

    /**
     * Returns platform's public key.
     * @return Public key.
     */
    public byte[] getPublicKey() {
        return mPublicKey;
    }

    /**
     * Returns platform's display name.
     * @return Platform's name.
     */
    public String getDisplayName() {
        return mDisplayName;
    }
}
