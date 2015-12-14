package ul.fcul.lasige.find.lib.data;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import com.google.common.collect.Sets;

import java.util.HashSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Created by hugonicolau on 13/11/15.
 *
 * /**
 * This class can be used to keep track of current FIND neighbors. It also takes an
 * {@link NeighborCallback} to be performed each time the neighbors change.
 *
 */
public class NeighborObserver extends ContentObserver {
    private static final String TAG = NeighborObserver.class.getSimpleName();

    // context of client app
    private final Context mContext;
    // URI neighbor changes with given protocol
    private final Uri mObservedUri;
    // callback for neighbor changes
    private final NeighborCallback mCallback;
    // caches current visible neighbors
    private final SortedSet<Neighbor> mCurrentNeighbors = new TreeSet<>();
    // caches the time of last update on neighbors list
    private long mTimeLastUpdate;

    /*
     * CONSTRUCTORS
     */
    public NeighborObserver(Context context, NeighborCallback callback) {
        super(new Handler());
        mContext = context.getApplicationContext();
        mObservedUri = FindContract.NeighborProtocols.URI_CURRENT;
        mCallback = callback;
    }

    public NeighborObserver(Handler handler, Context context, String protocolToken, NeighborCallback callback) {
        super(handler);

        mContext = context.getApplicationContext();
        // set URI for neighbor changes for given protocol
        mObservedUri = FindContract.buildProtocolUri(FindContract.NeighborProtocols.URI_CURRENT, protocolToken);
        mCallback = callback;
    }

    /**
     * Register the observer to start watching the current neighbors.
     */
    public void register() {
        mContext.getContentResolver().registerContentObserver(mObservedUri, false, this);
        updateCurrentNeighbors();
    }

    /**
     * Stop watching the current neighbors.
     */
    public void unregister() {
        mContext.getContentResolver().unregisterContentObserver(this);
        mCurrentNeighbors.clear();
    }

    /*
     * CONTENT OBSERVER CONTRACT
     */
    @Override
    public void onChange(boolean selfChange) {
        onChange(selfChange, null);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        updateCurrentNeighbors();
    }

    /**
     * Updates the neighbors. If NeighborObserver is registered for changes, this method is called
     * by the onChange method. Else, this method needs to be called before getNeighbors to get the
     * current neighbors.
     */
    public synchronized void updateCurrentNeighbors() {
        final Cursor neighborCursor = mContext.getContentResolver().query(
                mObservedUri,
                null,
                null,
                null,
                null);

        final HashSet<Neighbor> previousNeighbors = new HashSet<>(mCurrentNeighbors);
        synchronized (mCurrentNeighbors) {
            mCurrentNeighbors.clear();
            while (neighborCursor.moveToNext()) {
                Neighbor n = Neighbor.fromCursor(neighborCursor);
                mCurrentNeighbors.add(n);
            }
        }
        neighborCursor.close();

        if (mCallback != null) {
            for (Neighbor oldNeighbor : Sets.difference(previousNeighbors, mCurrentNeighbors)) {
                mCallback.onNeighborDisconnected(oldNeighbor);
            }
            for (Neighbor newNeighbor : Sets.difference(mCurrentNeighbors, previousNeighbors)) {
                mCallback.onNeighborConnected(newNeighbor);
            }
            mCallback.onNeighborsChanged(new HashSet<>(mCurrentNeighbors));
        }
    }

    /**
     * @return a copy of the current neighbor list.
     */
    synchronized public SortedSet<Neighbor> getCurrentNeighbors() {
        return new TreeSet<Neighbor>(mCurrentNeighbors);
    }

    /**
     * @return the current number of neighbors
     */
    synchronized public int getNeighborCount() {
        return mCurrentNeighbors.size();
    }

    /**
     * Callback interface for tasks that can be executed by the {@link NeighborObserver}.
     */
    public static interface NeighborCallback {
        public void onNeighborConnected(Neighbor currentNeighbor);

        public void onNeighborDisconnected(Neighbor recentNeighbor);

        public void onNeighborsChanged(Set<Neighbor> currentNeighbors);
    }
}
