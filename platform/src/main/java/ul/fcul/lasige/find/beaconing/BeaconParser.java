package ul.fcul.lasige.find.beaconing;

import android.content.ContentValues;
import android.util.Log;

import com.google.protobuf.InvalidProtocolBufferException;

import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import ul.fcul.lasige.find.data.Identity;
import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.network.NetworkManager;
import ul.fcul.lasige.find.protocolbuffer.FindProtos;
import ul.fcul.lasige.find.utils.ByteUtils;
import ul.fcul.lasige.find.utils.InterruptibleFailsafeRunnable;
import ul.fcul.lasige.find.data.FullContract.Neighbors;

/**
 * Class that extends from {@link InterruptibleFailsafeRunnable} and is able to parse
 * all beacons (valid and invalid) from neighbors.
 *
 * @see InterruptibleFailsafeRunnable
 *
 * Created by hugonicolau on 13/11/15.
 */
public class BeaconParser extends InterruptibleFailsafeRunnable {
    public static final String TAG = BeaconParser.class.getSimpleName();

    // beaconing manager
    private final BeaconingManager mBM;

    // queue of beacons to parse
    private final BlockingDeque<PossibleBeacon> mBeaconsToProcess = new LinkedBlockingDeque<>();
    // set of known beacons - used to identify repeated beacons
    private final Set<ByteBuffer> mKnownBeacons = new HashSet<>();
    // set of beacons that were processed
    private final Set<Integer> mProcessedBeacons = new HashSet<>();

    public BeaconParser(BeaconingManager context) {
        super(TAG);
        mBM = context;
    }

    /**
     * Add a beacon to be processed. Duplicate beacons are ignored.
     * @param newBeacon Beacon
     */
    public synchronized void addProcessableBeacon(PossibleBeacon newBeacon) {
        final ByteBuffer wrappedBeaconData = ByteBuffer.wrap(newBeacon.getRawData());

        if (mKnownBeacons.add(wrappedBeaconData)) {
            // It's a new beacon
            mBeaconsToProcess.add(newBeacon);
        }
    }

    /**
     * Clears all processed beacons.
     */
    public synchronized void clearProcessedBeacons() {
        mProcessedBeacons.clear();
    }

    /**
     * main thread; blocks waiting for beacons to process
     */
    @Override
    protected void execute() {
        while (!mThread.isInterrupted()) {
            PossibleBeacon nextBeacon;
            try {
                nextBeacon = mBeaconsToProcess.take();
            } catch (InterruptedException e) {
                // Parser got interrupted
                break;
            }

            parseSingleBeacon(nextBeacon);
        }
    }

    /**
     * Checks whether it is a valid beacon and parses it.
     * @param possibleBeacon Beacon
     */
    private void parseSingleBeacon(PossibleBeacon possibleBeacon) {
        Log.d(TAG, "Parsing Beacon");
        final byte[] rawData = possibleBeacon.getRawData();
        final byte[] origin = possibleBeacon.getOrigin();
        final byte[] ownNodeId = possibleBeacon.getNodeId();

        // check if it's a beacon, and if we still need to process it
        FindProtos.Beacon beacon;
        try {
            beacon = FindProtos.Beacon.parseFrom(rawData);
        } catch (InvalidProtocolBufferException e) {
            // this is not a Beacon message
            Log.e(TAG,
                    String.format(
                            "Received a %s packet from %s which is not a beacon!",
                            possibleBeacon.getSocketType().name().toLowerCase(Locale.US),
                            Arrays.toString(origin)),
                    e);
            return;
        }

        if (mProcessedBeacons.contains(beacon.getBeaconId())) {
            // this beacon has already been processed before
            return;
        }

        // it's a meaningful Beacon after all

        final String networkName = possibleBeacon.getNetworkName();
        final long referenceTimestamp = beacon.getTimeCreated(); // time beacon was originally created (remote time, careful)

        // beacon was successfully parsed, notify callback
        mBM.onBeaconParsed(beacon, possibleBeacon, referenceTimestamp);

        // register the sender as neighbor
        final FindProtos.Node sender = beacon.getSender();
        final ContentValues senderValues;
        try {
            senderValues = extractContent(sender, ownNodeId, networkName, referenceTimestamp);
        } catch (EmptyNodeIdException e) {
            Log.w(TAG, "Rejected a beacon with no sender id.");
            return;
        } catch (NodeIsUsException e) {
            // It's a packet from ourself
            Log.w(TAG, "Rejected a beacon from ourself "
                    + "(they should have be filtered out before parsing!).");
            return;
        }

        if (!senderIsOrigin(sender, origin)) {
            // mismatch, the beacon does not originate from the specified sender
            Log.w(TAG, "(Would have) Rejected a relayed beacon.");
            Log.v(TAG, beacon.toString());
            // TODO: fail again? some old devices don't get IPv6 addresses, but send multicasts
            // return;
        }
        senderValues.put(Neighbors.COLUMN_NETWORK, networkName);
        // set time last seen with our own local time, otherwise we have no control about sender's clock
        senderValues.put(Neighbors.COLUMN_TIME_LASTSEEN, possibleBeacon.getTimeReceived() /*TODO original - beacon.getTimeCreated()*/);

        // insert neighboor in database
        mBM.mDbController.insertNeighbor(senderValues, sender.getProtocolsList());
        Log.v(TAG, String.format(
                "Received a %s beacon (%s, %s bytes) from node %s",
                possibleBeacon.getSocketType().toString().toLowerCase(Locale.US),
                beacon.getBeaconType().toString().toLowerCase(Locale.US),
                rawData.length,
                ByteUtils.bytesToHex(sender.getNodeId(), Neighbor.BYTES_SHORT_NODE_ID)));

        // register sender's neighbors
        for (final FindProtos.Node neighbor : beacon.getNeighborsList()) {
            final ContentValues otherNeighborValues;
            try {
                otherNeighborValues = extractContent(
                        neighbor, ownNodeId, networkName, possibleBeacon.getTimeReceived() /*referenceTimestamp*/);
            } catch (EmptyNodeIdException e) {
                Log.w(TAG, "Skipped registering neighbor node with no node id.");
                continue;
            } catch (NodeIsUsException e) {
                // It's us!
                continue;
            }

            // insert sender's neighbors in database
            mBM.mDbController.insertNeighbor(otherNeighborValues, neighbor.getProtocolsList());
        }

        // finished processing beacon
        mProcessedBeacons.add(beacon.getBeaconId());
    }

    /**
     * Checks whether the sender actually created and sent the beacon.
     * @param sender Sender node.
     * @param originAddr Origin address.
     * @return true if the sender is the origin of the beacon, false otherwise.
     */
    private boolean senderIsOrigin(FindProtos.Node sender, byte[] originAddr) {
        String originType;

        switch (originAddr.length) {
            case 4: {
                // IPv4 address
                final byte[] senderIp4 = sender.getIp4Address().toByteArray();
                if (senderIp4.length > 0 && Arrays.equals(senderIp4, originAddr)) {
                    return true;
                }
                originType = "IPv4";
                break;
            }
            case 6: {
                // bluetooth address
                final byte[] btAddress = sender.getBtAddress().toByteArray();
                if (btAddress.length > 0 && Arrays.equals(btAddress, originAddr)) {
                    return true;
                }
                originType = "Bluetooth";
                break;
            }
            case 16: {
                // IPv6 address
                final byte[] senderIp6 = sender.getIp6Address().toByteArray();
                if (senderIp6.length > 0 && Arrays.equals(senderIp6, originAddr)) {
                    return true;
                }
                originType = "IPv6";
                break;
            }
            default: {
                // nothing we could use
                Log.w(TAG, String.format(
                        "Unknown origin %s (length: %d)",
                        ByteUtils.bytesToHex(originAddr), originAddr.length));
                return false;
            }
        }

        Log.w(TAG, String.format(
                "Origin %s (%s) is not the packet sender!",
                ByteUtils.bytesToHex(originAddr), originType));
        return false;
    }

    /**
     * Builds the {@link ContentValues} data structure from a {@link ul.fcul.lasige.find.protocolbuffer.FindProtos.Node}.
     * @param node Node.
     * @param ownNodeId Platform's node id.
     * @param networkName Network name.
     * @param referenceTime remote creation time.
     * @return A {@link ContentValues} object.
     * @throws EmptyNodeIdException
     * @throws NodeIsUsException
     * @see ContentValues
     */
    private ContentValues extractContent(FindProtos.Node node, byte[] ownNodeId,
                                         String networkName, long referenceTime)
            throws EmptyNodeIdException, NodeIsUsException {

        final byte[] nodeId = node.getNodeId().toByteArray();
        if (nodeId.length == 0) {
            // no node id information
            throw new EmptyNodeIdException();
        } else if (Arrays.equals(ownNodeId, nodeId)) {
            // we created this beacon
            throw new NodeIsUsException();
        }

        // build data structure
        final ContentValues values = new ContentValues();
        values.put(Neighbors.COLUMN_IDENTIFIER, nodeId);
        values.put(Neighbors.COLUMN_MULTICAST_CAPABLE, node.getMulticastCapable());
        values.put(Neighbors.COLUMN_TIME_LASTPACKET, 0); // default value

        if (node.hasDeltaLastseen()) {
            values.put(Neighbors.COLUMN_TIME_LASTSEEN, referenceTime - node.getDeltaLastseen());
        }
        if (node.hasIp4Address() || node.hasIp6Address()) {
            final String network = (node.hasNetwork() ? node.getNetwork() : networkName);
            values.put(Neighbors.COLUMN_NETWORK, network);

            if (node.hasIp4Address()) {
                values.put(Neighbors.COLUMN_IP4, node.getIp4Address().toByteArray());
            }
            if (node.hasIp6Address()) {
                values.put(Neighbors.COLUMN_IP6, node.getIp6Address().toByteArray());
            }
        }
        if (node.hasBtAddress()) {
            values.put(Neighbors.COLUMN_BLUETOOTH, node.getBtAddress().toByteArray());
        }

        return values;
    }

    /**
     * Exception for nodes without ids
     */
    private class EmptyNodeIdException extends Exception {
        private static final long serialVersionUID = 289865661932401267L;

        public EmptyNodeIdException() {
            super();
        }
    }

    /**
     * Exception for nodes that are us
     */
    private class NodeIsUsException extends Exception {
        private static final long serialVersionUID = -143212430785316974L;

        public NodeIsUsException() {
            super();
        }
    }

    /**
     * Represents a beacon that may be a beacon or not. It is not parsed, yet.
     */
    public static final class PossibleBeacon {
        private final byte[] mRawData;
        private final byte[] mOrigin;
        private final long mTimeReceived;
        private final String mReceivingNetworkName;
        private final BeaconingManager.SocketType mReceivingSocketType;
        private final byte[] mReceiverNodeId;

        /**
         * Builds a possible beacon from a set of parameters.
         * @param packet Packet.
         * @param timeReceived Time received.
         * @param networkName Network name.
         * @param socketType Socket type.
         * @param identity Identity.
         * @return A {@link PossibleBeacon} object.
         */
        public static PossibleBeacon from(DatagramPacket packet, long timeReceived,
                                          String networkName, BeaconingManager.SocketType socketType, Identity identity) {

            return new PossibleBeacon(
                    Arrays.copyOf(packet.getData(), packet.getLength()),
                    packet.getAddress().getAddress(),
                    timeReceived,
                    networkName,
                    socketType,
                    identity.getPublicKey());
        }

        /**
         * Builds a possible beacon from a set of parameters
         * @param buffer Buffer.
         * @param length Buffer's length.
         * @param timeReceived Time received.
         * @param btAddress Bluetooth address.
         * @param identity Identity.
         * @return A {@link PossibleBeacon} object.
         */
        public static PossibleBeacon from(byte[] buffer, int length, long timeReceived,
                                          String btAddress, Identity identity) {

            return new PossibleBeacon(
                    Arrays.copyOf(buffer, length),
                    NetworkManager.parseMacAddress(btAddress),
                    timeReceived,
                    null,
                    BeaconingManager.SocketType.RFCOMM,
                    identity.getPublicKey());
        }

        /**
         * Constructor.
         * @param rawData Data.
         * @param origin Origin node.
         * @param timeReceived Time received.
         * @param networkName Network name.
         * @param socketType Socket type.
         * @param receiverNodeId Receiver's node id.
         */
        public PossibleBeacon(byte[] rawData, byte[] origin, long timeReceived, String networkName,
                              BeaconingManager.SocketType socketType, byte[] receiverNodeId) {
            mRawData = Arrays.copyOf(rawData, rawData.length);
            mOrigin = origin;
            mTimeReceived = timeReceived;
            mReceivingNetworkName = networkName;
            mReceivingSocketType = socketType;
            mReceiverNodeId = receiverNodeId;
        }

        /**
         * Retrieves the possible beacon's raw data.
         * @return Raw data.
         */
        public byte[] getRawData() {
            return mRawData;
        }

        /**
         * Retrieves the possible beacon's origin.
         * @return Origin.
         */
        public byte[] getOrigin() {
            return mOrigin;
        }

        /**
         * Retrieves the possible beacon's time receive.
         * @return Time received.
         */
        public long getTimeReceived() {
            return mTimeReceived;
        }

        /**
         * Retrieves the network's name.
         * @return Network name.
         */
        public String getNetworkName() {
            return mReceivingNetworkName;
        }

        /**
         * Retrieves the socket type of the possible beacon.
         * @return Socket type.
         * @see ul.fcul.lasige.find.beaconing.BeaconingManager.SocketType
         */
        public BeaconingManager.SocketType getSocketType() {
            return mReceivingSocketType;
        }

        /**
         * Retrieves the node id of the possible beacon.
         * @return Node id.
         */
        public byte[] getNodeId() {
            return mReceiverNodeId;
        }
    }
}
