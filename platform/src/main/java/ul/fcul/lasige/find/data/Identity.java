package ul.fcul.lasige.find.data;

import android.database.Cursor;

/**
 * Created by hugonicolau on 05/11/2015.
 *
 * Stores the public key of this FIND platform
 */
public class Identity {
    private final byte[] mPublicKey;
    private final String mDisplayName;

    public static Identity fromCursor(Cursor cursor) {
        final byte[] publicKey =
                cursor.getBlob(cursor.getColumnIndex(FullContract.Identities.COLUMN_PUBLICKEY));
        final String displayName =
                cursor.getString(cursor.getColumnIndex(FullContract.Identities.COLUMN_DISPLAY_NAME));

        return new Identity(publicKey, displayName);
    }

    private Identity(byte[] publicKey, String displayName) {
        mPublicKey = publicKey;
        mDisplayName = displayName;
    }

    public byte[] getPublicKey() {
        return mPublicKey;
    }

    public String getDisplayName() {
        return mDisplayName;
    }
}
