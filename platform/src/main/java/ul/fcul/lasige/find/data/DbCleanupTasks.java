package ul.fcul.lasige.find.data;

import android.content.Context;
import android.util.Log;

import ul.fcul.lasige.find.packetcomm.PacketRegistry;

/**
 * Created by hugonicolau on 18/11/15.
 */
public class DbCleanupTasks {

    public static class ExpiredPacketsCleanupTask implements Runnable {
        private static final String TAG = ExpiredPacketsCleanupTask.class.getSimpleName();
        private final Context mContext;

        public ExpiredPacketsCleanupTask(Context context) {
            super();
            mContext = context;
        }

        @Override
        public void run() {
            final DbController dbController = new DbController(mContext);

            final long currentTime = System.currentTimeMillis() / 1000;
            final int deleteCount = dbController.deleteExpiredPackets(currentTime);
            Log.v(TAG, String.format("Deleted %d packets with a TTL lower than %d", deleteCount, currentTime));

            // check whether packets were deleted
            if(deleteCount > 0) {
                // update packet registry
                PacketRegistry.getInstance(mContext).fillPacketCaches();
            }

            // reset timestamp of last packet sent to all neighbors in order to force
            // re-send of all packets when neighbor is reachable
            dbController.resetNeighborsTimeLastPacket();
        }
    }
}
