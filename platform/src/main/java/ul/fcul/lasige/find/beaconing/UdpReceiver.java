package ul.fcul.lasige.find.beaconing;

import android.util.Log;

import com.google.common.base.Optional;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;

import ul.fcul.lasige.find.network.WifiConnection;
import ul.fcul.lasige.find.utils.InterruptibleFailsafeRunnable;
import ul.fcul.lasige.find.beaconing.BeaconParser.PossibleBeacon;

/**
 * Class extends {@link InterruptibleFailsafeRunnable} and is responsible for receiving beacons from
 * neighbors.
 *
 * Created by hugonicolau on 13/11/15.
 */
public abstract class UdpReceiver extends InterruptibleFailsafeRunnable {
    public static final String TAG = UdpReceiver.class.getSimpleName();

    /**
     * The first two bytes of a "Multicast DNS" packet. FIND sends beacons to the multicast groups
     * defined in the mDNS standard, so we might also receive actual mDNS packets, although our own
     * beacon format is different (and never starts with the same header bytes).
     */
    private static final byte[] MDNS_HEAD = { 0x00, 0x00 };

    // beaconing manager
    private final BeaconingManager mBM;
    // socket type
    private final BeaconingManager.SocketType mSocketType;
    // socket
    private final DatagramSocket mSocket;

    /**
     * Constructor. It creates the socket.
     * @param context Beaconing manager
     * @param socketType Socket type
     * @throws IOException
     */
    public UdpReceiver(BeaconingManager context, BeaconingManager.SocketType socketType) throws IOException {
        super(TAG);
        mBM = context;
        mSocketType = socketType;
        mSocket = createSocket();
    }

    protected abstract DatagramSocket createSocket() throws IOException;

    /**
     * Checks whether packet is ours
     * @param packet Datagram packet
     * @param connection WiFi connection
     * @return true if packet was sent by us, false otherwise.
     */
    private boolean isOwnPacket(DatagramPacket packet, WifiConnection connection) {
        // get packet address
        final InetAddress senderAddress = packet.getAddress();
        if (senderAddress instanceof Inet4Address && connection.hasIp4Address()) {
            // it is an ipv4 address
            return senderAddress.equals(connection.getIp4Address().get());
        } else if (senderAddress instanceof Inet6Address && connection.hasIp6Address()) {
            // it is an ipv6 address
            return senderAddress.equals(connection.getIp6Address().get());
        }
        // unrecognized address
        return false;
    }

    /**
     * Main thread that listens for beacons.
     */
    @Override
    protected void execute() {
        // start receiving beacons
        // create buffer
        byte[] buffer = new byte[BeaconingManager.RECEIVER_BUFFER_SIZE];
        // create packet
        final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        // run until it is interrupted by beaconing manager
        while (!mThread.isInterrupted()) {
            // Android sometimes limits the incoming packet size to the previously received
            // packet size. The following call circumvents this problem.
            packet.setData(buffer);

            // connection
            final WifiConnection wifiConnection;
            try {
                // listen/blocks until a datagram packet is received
                mSocket.receive(packet);

                // we received a datagram packet!

                // get current connection
                Optional<WifiConnection> conn = mBM.mNetManager.getCurrentConnection();
                if (!conn.isPresent()) {
                    // the wifi is disconnected, stop listening
                    break;
                }
                wifiConnection = conn.get();
            } catch (SocketTimeoutException e) {
                // no beacon received, try again
                continue;
            } catch (IOException e) {
                Log.e(TAG, "Error while receiving beacon, aborting.", e);
                break;
            }
            // check time
            final long timeReceived = System.currentTimeMillis() / 1000;

            // skip if packet is empty, from ourselves or real mDNS
            if (packet.getLength() == 0
                    || isOwnPacket(packet, wifiConnection)
                    || (packet.getData()[0] == MDNS_HEAD[0] && packet.getData()[1] == MDNS_HEAD[1])) {
                Log.d(TAG, "packet is empty or is my own packet");
                continue;
            }

            // it is a possible beacon, decode packet
            final PossibleBeacon possibleBeacon = PossibleBeacon.from(
                    packet, timeReceived, wifiConnection.getNetworkName().get(),
                    mSocketType, mBM.mMasterIdentity);

            // notify callback, which will add it to BeaconParser queue
            mBM.onBeaconReceived(possibleBeacon);
        }

        // destroy socket
        mSocket.close();
    }

    // IMPLEMENTATIONS
    /*TODO public static class UdpMulticastReceiver extends UdpReceiver {
        public UdpMulticastReceiver(BeaconingManager context) throws IOException {
            super(context, BeaconingManager.SocketType.MULTICAST);
        }

        @Override
        protected DatagramSocket createSocket() throws IOException {
            MulticastSocket socket = new MulticastSocket(null);
            socket.setSoTimeout(BeaconingManager.RECEIVER_SOCKET_TIMEOUT);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(BeaconingManager.RECEIVER_PORT_MULTICAST));

            for (InetAddress multicastGroup : BeaconingManager.MULTICAST_GROUPS) {
                socket.joinGroup(multicastGroup);
            }
            return socket;
        }
    }*/

    /**
     * Implementation of a UDP unicast receiver, which extends {@link UdpReceiver}. It contains a
     * constructor and {@link UdpReceiver#createSocket()} method.
     */
    public static class UdpUnicastReceiver extends UdpReceiver {
        public UdpUnicastReceiver(BeaconingManager context) throws IOException {
            super(context, BeaconingManager.SocketType.UNICAST);
        }

        /**
         * Creates a {@link DatagramSocket} at port {@link BeaconingManager#RECEIVER_PORT_UNICAST} with
         * {@link BeaconingManager#RECEIVER_SOCKET_TIMEOUT} timeout.
         * @return A {@link DatagramSocket}.
         * @throws IOException
         */
        @Override
        protected DatagramSocket createSocket() throws IOException {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setSoTimeout(BeaconingManager.RECEIVER_SOCKET_TIMEOUT);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(BeaconingManager.RECEIVER_PORT_UNICAST));
            return socket;
        }
    }
}
