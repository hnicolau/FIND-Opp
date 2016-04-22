package ul.fcul.lasige.find.lib.data;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import com.google.common.base.Objects;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by hugonicolau on 03/11/2015.
 *
 * This class extends ContentObserver. It receives callbacks to content changes (incoming packets).
 *
 * @see ContentObserver
 */
public class PacketObserver extends ContentObserver {
    private static final String TAG = InternetObserver.class.getSimpleName();


    // context of client app
    private final Context mContext;
    // URI incoming packets
    private final Uri mObservedUri;
    // callback for incoming packets
    private final PacketCallback mCallback;
    // caches timestamp of last received packet
    private long mTimeLastPacketReceived;

    /**
     * Constructor for PacketObserver.
     *
     * @param handler Handler to run onChange.
     * @param context Context of client app.
     * @param protocolToken Protocol token for PacketObserver.
     * @param callback Callback that will be called onPacketReceived.
     */
    public PacketObserver(Handler handler, Context context, String protocolToken, PacketCallback callback) {
        super(handler);

        mContext = context;
        // set URI to incoming packet
        mObservedUri = FindContract.buildProtocolUri(FindContract.Packets.URI_INCOMING, protocolToken);
        mCallback = callback;
        // get persistently stored timestamp of last packet; this ensure that even when client app
        // is not connected with the server, it is still able to retrieve all incoming packets
        mTimeLastPacketReceived = TokenStore.getLastPacketReceived(mContext);
    }

    /**
     * Register PacketObserver with the FIND platform. From this moment on, the callback will be
     * called for all received packets.
     */
    public void register() {
        // register content resolver for changes on incoming packets table
        mContext.getContentResolver().registerContentObserver(mObservedUri, true, this);
    }

    /**
     * Unregister PacketObserver with the FIND platform. Callback will no longer be called for
     * received packets.
     */
    public void unregister() {
        mContext.getContentResolver().unregisterContentObserver(this);
    }

    /*
     * CONTENT OBSERVER CONTRACT
     */

    /**
     * Method is called when content (i.e. incoming packets table) in the FIND platform is changed.
     *
     * @param selfChange Indicates whether it was a self change.
     */
    @Override
    public void onChange(boolean selfChange) {
        Log.d(TAG, "Received self Packect Update");

        onChange(selfChange, null);
    }

    /**
     * Method is called when content (i.e. incoming packets table) in the FIND platform is changed.
     *
     * @param selfChange Indicates whether it was a self change.
     * @param uri URI of the change in content.
     */
    @Override
    public void onChange(boolean selfChange, Uri uri) {
        Log.d(TAG, "Received Packect Update");

        if(uri!=null)
            Log.d(TAG, "Received Packect Update" + uri.toString());
        // get all packets since last one received; this is particularly useful if client app was
        // disconnected and the platform received new packets
        for (Packet packet : getPacketsSince(mTimeLastPacketReceived)) {
            // set timestamp to last packet received; Math.max is to guarantee we don't go back in time
            mTimeLastPacketReceived = Math.max(mTimeLastPacketReceived, packet.getTimeReceived());
            // notifies client app of new packet received
            mCallback.onPacketReceived(packet,uri);
            //mCallback.onPacketReceived(packet);
        }
        // save last timestamp persistently
        TokenStore.saveLastPacketReceived(mContext, mTimeLastPacketReceived);
    }

    /*
     * OBJECT CONTRACT
     */

    /**
     * Generates a hash code for the PacketObserver. The hash code is based on the URI for incoming
     * packets.
     *
     * @return returns the hashcode of the object.
     */
    @Override
    public int hashCode() { return Objects.hashCode(mObservedUri.toString()); }

    /**
     *
     * @return Returns a string representation of the object based on the URI for incoming packets.
     */
    @Override
    public String toString() {
        return Objects.toStringHelper(this).add("uri", mObservedUri).toString();
    }

    /*
     * CALLBACK INTERFACE FOR FIND CLIENT
     */
    public static interface PacketCallback {
        //public void onPacketReceived(Packet packet);
        public void onPacketReceived(Packet packet, Uri uri);
    }

    /*
     * HELPER METHODS
     */

    /**
     * Get a list of packets that were received by the FIND service since the given timestamp. It
     * uses a ContentResolver to obtain this list.
     *
     * @param timestamp Value in time that represents the last packet received.
     * @return List of packets received by the FIND platform since given timestamp.
     *
     * @see android.content.ContentResolver
     */
    public List<Packet> getPacketsSince(long timestamp) {
        final ArrayList<Packet> newPackets = new ArrayList<>();
        // call Platform.data.FindProvider
        final Cursor newPacketsCursor = mContext.getContentResolver().query(
                mObservedUri,
                null,
                FindContract.Packets.COLUMN_TIME_RECEIVED + " > ?",
                new String[] { String.valueOf(timestamp) },
                null);

        while (newPacketsCursor.moveToNext()) {
            newPackets.add(Packet.fromCursor(newPacketsCursor));
        }
        newPacketsCursor.close();
        return newPackets;
    }


}
