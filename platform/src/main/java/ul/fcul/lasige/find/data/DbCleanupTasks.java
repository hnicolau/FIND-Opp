package ul.fcul.lasige.find.data;

import android.content.Context;
import android.util.Log;

import ul.fcul.lasige.find.packetcomm.PacketRegistry;

/**
 * Garbage collector responsible for cleaning expired packets based on their TTL. It also resets neighbors'
 * timestamps of last sent packet.
 *
 * Created by hugonicolau on 18/11/15.
 */
public class DbCleanupTasks {

    /**
     * Runnable that executes garbage collector task.
     */
    public static class ExpiredPacketsCleanupTask implements Runnable {
        private static final String TAG = ExpiredPacketsCleanupTask.class.getSimpleName();
        private final Context mContext;

        /**
         * Constructor
         * @param context Application context
         */
        public ExpiredPacketsCleanupTask(Context context) {
            super();
            mContext = context;
        }

        @Override
        public void run() {
            // create database controller
            final DbController dbController = new DbController(mContext);

            // get current time in minutes
            final long currentTime = System.currentTimeMillis() / 1000;
                Log.d(TAG, "Deleting counting ");

                // delete expired packets from database that were already synchronized
                final int deleteCount = dbController.deleteExpiredPackets(currentTime);
                Log.d(TAG, "Deleting count" + deleteCount);

                 //remove expire packets from the outgoing view
                 dbController.updateOutgoingView(currentTime);

                //remove expire packets from the outgoing view
                dbController.updateStaleView(currentTime);

                Log.v(TAG, String.format("Deleted %d packets with a TTL lower than %d", deleteCount, currentTime));

                    // update packet registry
                    PacketRegistry.getInstance(mContext).fillPacketCaches();


            // reset timestamp of last packet sent to all neighbors in order to force
            // re-send of all packets when neighbor is reachable
            dbController.resetNeighborsTimeLastPacket();
        }
    }
}
