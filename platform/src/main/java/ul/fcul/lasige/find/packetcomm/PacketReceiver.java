package ul.fcul.lasige.find.packetcomm;

import android.util.Log;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ul.fcul.lasige.find.apps.ProtocolRegistry;
import ul.fcul.lasige.find.data.FullContract;
import ul.fcul.lasige.find.data.Identity;
import ul.fcul.lasige.find.protocolbuffer.FindProtos;
import ul.fcul.lasige.find.utils.InterruptibleFailsafeRunnable;

/**
 * The class extends {@link InterruptibleFailsafeRunnable} and is responsible for waiting and receiving (listen)
 * for incoming packets from neighbors.
 *
 * Created by hugonicolau on 17/11/15.
 */
public class PacketReceiver extends InterruptibleFailsafeRunnable {
    private static final String TAG = PacketReceiver.class.getSimpleName();

    // timeout
    private static final int SOCKET_TIMEOUT = 5000;
    // incoming data buffer size
    private static final int BUFFER_SIZE = 65536;

    // packet registry, use to register incoming packets
    private final PacketRegistry mPacketRegistry;
    // public key
    private final Identity mIdentity;
    // protocol registry, used to check whether we support packets' protocols
    private final ProtocolRegistry mProtocolRegistry;

    /**
     * Constructor.
     * @param packetRegistry Packet registry.
     * @param identity Platform's identity.
     * @param protocolRegistry Protocol registry.
     */
    public PacketReceiver(PacketRegistry packetRegistry, Identity identity, ProtocolRegistry protocolRegistry) {
        super(TAG);
        mPacketRegistry = packetRegistry;
        mIdentity = identity;
        mProtocolRegistry = protocolRegistry;
    }

    /**
     * Main thread.
     */
    @Override
    public void execute() {
        DatagramSocket socket = null;
        try {
            // create socket
            socket = new DatagramSocket(PacketSenderService.PACKET_RECEIVING_PORT);
            // set timeout
            socket.setSoTimeout(SOCKET_TIMEOUT);
        } catch (SocketException e) {
            Log.e(TAG, "Could not create socket to receive TransportPackets", e);
            if (socket != null) {
                socket.close();
            }
            return;
        }

        // data buffer
        byte[] buffer = new byte[BUFFER_SIZE];
        // create datagram packet
        DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);

        // while no one interrupts us
        while (!mThread.isInterrupted()) {
            // Android sometimes limits the incoming packet size to the previously received
            // packet size. The following call circumvents this problem.
            udpPacket.setData(buffer);

            try {
                //Log.d(TAG, "Waiting for packets ....");
                // wait for packets, this blocks the thread for a given timeout
                socket.receive(udpPacket);
            } catch (SocketTimeoutException e) {
                // there were no packets in the past few seconds - try again.
                //Log.d(TAG, "There were no packet in the past few seconds, going to try again ...");
                continue;
            } catch (IOException e) {
                Log.e(TAG, "Error while receiving TransportPacket:", e);
                continue;
            }

            // packet received!
            final FindProtos.TransportPacket incomingPacket;
            try {
                // build incoming packet
                incomingPacket = FindProtos.TransportPacket.parseFrom(Arrays.copyOf(udpPacket.getData(), udpPacket.getLength()));
            } catch (InvalidProtocolBufferException e) {
                // not a TransportPacket, skip
                continue;
            }

            // calculate packet queues to put the incoming packet into
            final List<FullContract.PacketQueues> queue = new ArrayList<>();

            // are we the target node?
            final boolean isReceiver = (incomingPacket.hasTargetNode() && Arrays.equals(
                    incomingPacket.getTargetNode().toByteArray(), mIdentity.getPublicKey()));
            // get protocol
            final ByteBuffer protocol = incomingPacket.getProtocol().asReadOnlyByteBuffer();
            // do we support this protocol?
            final boolean supportedProtocol = mProtocolRegistry.hasProtocolImplementations(protocol);

            if (isReceiver) {
                // we are the target!
                if (!supportedProtocol) {
                    // The packet is targeted at us, but there is no client app installed
                    // which implements the protocol (otherwise, the 'if' clause would have
                    // consumed the packet already) - reject this packet.
                    Log.v(TAG, "Rejecting incoming packet, protocol unknown");
                    continue;
                }

                // add it to the queue
                Log.v(TAG, "Adding incoming packet (targeted) to INCOMING queue");
                queue.add(FullContract.PacketQueues.INCOMING);
            } else {
                // no target node always means "FORWARDING"
                Log.v(TAG, "Adding incoming packet to FORWARDING queue");
                queue.add(FullContract.PacketQueues.FORWARDING);

                if (!incomingPacket.hasTargetNode() && supportedProtocol) {
                    // if we support the protocol and the packet has no target, then add it to incoming
                    Log.v(TAG, "Adding incoming packet (untargeted) to INCOMING queue");
                    queue.add(FullContract.PacketQueues.INCOMING);
                }
            }

            // register packet in queue list
            mPacketRegistry.registerIncomingPacket(incomingPacket, queue.toArray(new FullContract.PacketQueues[] {}));
        }

        // close socket when we are interrupted
        socket.close();
    }
}
