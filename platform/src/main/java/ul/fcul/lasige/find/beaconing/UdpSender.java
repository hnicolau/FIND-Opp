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
 * Created by hugonicolau on 13/11/15.
 */
public class UdpSender extends InterruptibleFailsafeRunnable {
    public static final String TAG = "WifiSender";

    private final BeaconingManager mBM;
    //TODO private final boolean mPerformSubnetSweep;
    private final int mBurstSize;
    private final byte mApLikelihood;

    private final InetAddress mReplyTo;
    private final FindProtos.Beacon mReceivedBeacon;

    private DatagramSocket mSocket; //TODO original was datagramsocket

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

    public UdpSender(BeaconingManager context, boolean subnetSweep, int burstSize, int apLikelihood) {
        super(TAG);
        mBM = context;
        //TODO mPerformSubnetSweep = subnetSweep;
        mBurstSize = burstSize;
        mApLikelihood = (byte) (apLikelihood & 0xFF);

        mReplyTo = null;
        mReceivedBeacon = null;
    }

    @Override
    public void execute() {
        final NetworkManager.WifiState wifiState = mBM.mNetManager.getWifiState();
        final Optional<WifiConnection> wifiConnection = mBM.mNetManager.getCurrentConnection();
        if (wifiState.equals(NetworkManager.WifiState.DISCONNECTED) || !wifiConnection.isPresent()) {
            return;
        }

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
            switch (wifiState) {
                case FIND_AP: {
                    // Send beacon to all neighbors (using unicast)
                    addNeighborsAsUnicastTargets(receivers, neighbors);
                    break;
                }
                case STA_ON_FIND_AP: {
                    // Send beacon to access point node (using unicast), but nobody else
                    receivers.add(new InetSocketAddress(
                            connection.getApAddress().get(),
                            BeaconingManager.RECEIVER_PORT_UNICAST));
                    break;
                }
                case STA_ON_PUBLIC_AP: {
                    // Send beacon to all neighbors (using unicast) and all multicast groups
                    addNeighborsAsUnicastTargets(receivers, neighbors);
                    /*addMulticastTargets(receivers);
                    TODO if (mPerformSubnetSweep) {
                        addUnicastSweepTargets(receivers);
                    }*/
                    break;
                }
                default: {
                    // No receivers!
                    return;
                }
            }
        }

        if (receivers.isEmpty()) {
            // no one to send beacons
            return;
        }

        // Build beacon
        final Set<ByteBuffer> protocols = mBM.mProtocolRegistry.getAllProtocolImplementations().keySet();

        final byte[] beaconData;
        if (mReplyTo != null) {
            // reply data
            beaconData = mBM.mBeaconBuilder.buildReply(wifiState, wifiConnection, protocols, neighbors, mReceivedBeacon);
        }
        else if (wifiState.equals(NetworkManager.WifiState.STA_ON_FIND_AP)) {
            // data to send to FIND AP
            beaconData = mBM.mBeaconBuilder.buildBeacon(wifiState, wifiConnection, protocols, mApLikelihood);
        }
        else {
            // data to send to all neighbors, we are a FIND AP
            beaconData = mBM.mBeaconBuilder.buildBeacon(wifiState, wifiConnection, protocols, neighbors);
        }

        // Create socket and send data
        try {
            mSocket = createSocket(connection.getWifiInterface());
        } catch (IOException e) {
            Log.e(TAG, "Could not create socket to send beacon:", e);
            return;
        }

        final DatagramPacket packet = new DatagramPacket(beaconData, beaconData.length);
        for (InetSocketAddress receiver : receivers) {
            if (mThread.isInterrupted()) {
                break;
            }

            packet.setSocketAddress(receiver);
            try {
                for (int i = 0; i < mBurstSize; i++) {
                    mSocket.send(packet);
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not create socket to send beacon to " + receiver);
            }
        }

        mSocket.close();
        Log.v(TAG, String.format(
                "Tried to send %d beacons (size: %d bytes) to %d receivers",
                mBurstSize, beaconData.length, receivers.size()));
    }

    private MulticastSocket createSocket(NetworkInterface wifiInterface) throws IOException {
        final MulticastSocket socket = new MulticastSocket(null);
        socket.setSoTimeout(5000);
        socket.setReuseAddress(true);
        socket.setLoopbackMode(true);
        socket.setBroadcast(true);
        socket.setNetworkInterface(wifiInterface);
        return socket;
    }

    private void addNeighborsAsUnicastTargets(
            List<InetSocketAddress> receivers, Set<Neighbor> neighbors) {

        for (Neighbor neighbor : neighbors) {
            if (neighbor.hasAnyIpAddress()) {
                receivers.add(new InetSocketAddress(
                        neighbor.getAnyIpAddress(), BeaconingManager.RECEIVER_PORT_UNICAST));
            }
        }
    }

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
