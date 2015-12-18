package ul.fcul.lasige.find.beaconing;

import android.util.Log;

import com.google.common.base.Optional;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.network.NetworkManager;
import ul.fcul.lasige.find.network.WifiConnection;
import ul.fcul.lasige.find.protocolbuffer.FindProtos;
import ul.fcul.lasige.find.utils.InterruptibleFailsafeRunnable;

/**
 * Class extends {@link InterruptibleFailsafeRunnable} and is responsible for sending beacons trough UDP.
 *
 * Created by hugonicolau on 13/11/15.
 */
public class UdpSender extends InterruptibleFailsafeRunnable {
    public static final String TAG = UdpSender.class.getSimpleName();

    // beaconing manager
    private final BeaconingManager mBM;
    //TODO private final boolean mPerformSubnetSweep;
    // burst size
    private final int mBurstSize;
    // AP likelihood
    private final byte mApLikelihood;

    // address to reply to (can be null)
    private final InetAddress mReplyTo;
    // received beacon; it's not null when we are replying
    private final FindProtos.Beacon mReceivedBeacon;

    // sender's socket
    private DatagramSocket mSocket;

    /**
     * Constructor to reply to a neighbor beacon.
     * @param context {@link BeaconingManager} object.
     * @param replyTo Address to reply to.
     * @param receivedBeacon Received beacon
     * @param apLikelihood AP likelihood
     */
    public UdpSender(BeaconingManager context, InetAddress replyTo,
                     FindProtos.Beacon receivedBeacon, int apLikelihood) {

        super(TAG);
        mBM = context;
        //TODO mPerformSubnetSweep = false;
        mBurstSize = 1;
        mApLikelihood = (byte) (apLikelihood & 0xFF);

        mReplyTo = replyTo;
        mReceivedBeacon = receivedBeacon;
    }

    /**
     * Constructor to send a beacon.
     * @param context {@link BeaconingManager} object.
     * @param subnetSweep Perform subnet sweep?
     * @param burstSize Burst size.
     * @param apLikelihood AP likelihood.
     */
    public UdpSender(BeaconingManager context, boolean subnetSweep, int burstSize, int apLikelihood) {
        super(TAG);
        mBM = context;
        //TODO mPerformSubnetSweep = subnetSweep;
        mBurstSize = burstSize;
        mApLikelihood = (byte) (apLikelihood & 0xFF);

        mReplyTo = null;
        mReceivedBeacon = null;
    }

    /**
     * Main thread.
     */
    @Override
    public void execute() {
        // check WiFi state and connection
        final NetworkManager.WifiState wifiState = mBM.mNetManager.getWifiState();
        final Optional<WifiConnection> wifiConnection = mBM.mNetManager.getCurrentConnection();
        if (wifiState.equals(NetworkManager.WifiState.DISCONNECTED) || !wifiConnection.isPresent()) {
            return;
        }

        // get connection
        final WifiConnection connection = wifiConnection.get();

        // build receiver list
        final List<InetSocketAddress> receivers = new ArrayList<>();
        // get list of current neighbors
        final Set<Neighbor> neighbors = mBM.mDbController.getNeighbors(BeaconingManager.getCurrentTimestamp());

        if (mReplyTo != null) {
            // reply to previously received beacon
            receivers.add(new InetSocketAddress(mReplyTo, BeaconingManager.RECEIVER_PORT_UNICAST));
        }
        else {
            // it's not a reply, the list of neighbors depends on our network state
            switch (wifiState) {
                case FIND_AP: {
                    // we are AP -> send beacon to all neighbors (using unicast)
                    addNeighborsAsUnicastTargets(receivers, neighbors);
                    break;
                }
                case STA_ON_FIND_AP: {
                    // we are connected to an AP -> send beacon to access point node (using unicast), but nobody else
                    receivers.add(new InetSocketAddress(
                            connection.getApAddress().get(),
                            BeaconingManager.RECEIVER_PORT_UNICAST));
                    break;
                }
                case STA_ON_PUBLIC_AP: {
                    // we are on a public network -> send beacon to all neighbors (using unicast) and all multicast groups
                    addNeighborsAsUnicastTargets(receivers, neighbors);
                    /*addMulticastTargets(receivers);
                    TODO if (mPerformSubnetSweep) {
                        addUnicastSweepTargets(receivers);
                    }*/
                    break;
                }
                default: {
                    // no receivers!
                    return;
                }
            }
        }

        if (receivers.isEmpty()) {
            // no one to send beacons
            return;
        }

        // Build beacon
        // get protocols' hash values
        final Set<ByteBuffer> protocols = mBM.mProtocolRegistry.getAllProtocolImplementations().keySet();

        // build beacon's data
        final byte[] beaconData;
        if (mReplyTo != null) {
            // reply data
            beaconData = mBM.mBeaconBuilder.buildReply(wifiState, wifiConnection, protocols, neighbors, mReceivedBeacon);
        }
        else if (wifiState.equals(NetworkManager.WifiState.STA_ON_FIND_AP)) {
            // data to send to FIND AP (no neighbor information)
            beaconData = mBM.mBeaconBuilder.buildBeacon(wifiState, wifiConnection, protocols, mApLikelihood);
        }
        else {
            // data to send to all neighbors, we are a FIND AP or on a network
            beaconData = mBM.mBeaconBuilder.buildBeacon(wifiState, wifiConnection, protocols, neighbors);
        }

        // create socket and send data
        try {
            // create
            mSocket = createSocket(connection.getWifiInterface());
        } catch (IOException e) {
            Log.e(TAG, "Could not create socket to send beacon:", e);
            return;
        }

        // create datagram packet with data and length
        final DatagramPacket packet = new DatagramPacket(beaconData, beaconData.length);
        // for each of the receivers
        for (InetSocketAddress receiver : receivers) {
            if (mThread.isInterrupted()) {
                break;
            }
            // set address
            packet.setSocketAddress(receiver);
            try {
                // for a number of bursts
                for (int i = 0; i < mBurstSize; i++) {
                    // send beacon to neighbor(s)
                    mSocket.send(packet);
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not create socket to send beacon to " + receiver);
            }
        }

        // close socket
        mSocket.close();
        Log.v(TAG, String.format(
                "Tried to send %d beacons (size: %d bytes) to %d receivers",
                mBurstSize, beaconData.length, receivers.size()));
    }

    /**
     * Create socket for a given network interface and with a 5 second timeout. Notice that a {@link MulticastSocket} also works for Unicast.
     * @param wifiInterface WiFi interface.
     * @return A {@link MulticastSocket} object.
     * @throws IOException
     */
    private MulticastSocket createSocket(NetworkInterface wifiInterface) throws IOException {
        final MulticastSocket socket = new MulticastSocket(null);
        // set timeout
        socket.setSoTimeout(5000);
        socket.setReuseAddress(true);
        socket.setLoopbackMode(true);
        socket.setBroadcast(true);
        socket.setNetworkInterface(wifiInterface);
        return socket;
    }

    /**
     * Add set of neighbors to a given list of addresses.
     * @param receivers List of addresses.
     * @param neighbors Set of neighbors
     * @see InetSocketAddress
     * @see Neighbor
     */
    private void addNeighborsAsUnicastTargets(
            List<InetSocketAddress> receivers, Set<Neighbor> neighbors) {

        for (Neighbor neighbor : neighbors) {
            if (neighbor.hasAnyIpAddress()) {
                receivers.add(new InetSocketAddress(
                        neighbor.getAnyIpAddress(), BeaconingManager.RECEIVER_PORT_UNICAST));
            }
        }
    }

    /**
     * Add udp receivers for IPv4 range to a list of receivers.
     * @param receivers List to where receivers will be added.
     * @see NetworkManager#getIp4SweepRange()
     */
    private void addUnicastSweepTargets(List<InetSocketAddress> receivers) {
        for (InetAddress addr : mBM.mNetManager.getIp4SweepRange()) {
            receivers.add(new InetSocketAddress(addr, BeaconingManager.RECEIVER_PORT_UNICAST));
        }
    }

    /*private void addMulticastTargets(List<InetSocketAddress> receivers) {
        for (InetAddress multicastGroup : BeaconingManager.MULTICAST_GROUPS) {
            receivers.add(new InetSocketAddress(
                    multicastGroup, BeaconingManager.RECEIVER_PORT_MULTICAST));
        }
    }*/
}
