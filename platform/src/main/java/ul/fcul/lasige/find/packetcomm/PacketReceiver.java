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
 * Created by hugonicolau on 17/11/15.
 */
public class PacketReceiver extends InterruptibleFailsafeRunnable {
    private static final String TAG = PacketReceiver.class.getSimpleName();

    private static final int SOCKET_TIMEOUT = 5000;
    private static final int BUFFER_SIZE = 65536;

    private final PacketRegistry mPacketRegistry;
    private final Identity mIdentity;
    private final ProtocolRegistry mProtocolRegistry;

    public PacketReceiver(PacketRegistry packetRegistry, Identity identity, ProtocolRegistry protocolRegistry) {
        super(TAG);
        mPacketRegistry = packetRegistry;
        mIdentity = identity;
        mProtocolRegistry = protocolRegistry;
    }

    @Override
    public void execute() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(PacketSenderService.PACKET_RECEIVING_PORT);
            socket.setSoTimeout(SOCKET_TIMEOUT);
        } catch (SocketException e) {
            Log.e(TAG, "Could not create socket to receive TransportPackets", e);
            if (socket != null) {
                socket.close();
            }
            return;
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
        while (!mThread.isInterrupted()) {
            // Android sometimes limits the incoming packet size to the previously received
            // packet size. The following call circumvents this problem.
            udpPacket.setData(buffer);

            try {
                //Log.d(TAG, "Waiting for packets ....");
                socket.receive(udpPacket);
            } catch (SocketTimeoutException e) {
                // There were no packets in the past few seconds - try again.
                //Log.d(TAG, "There were no packet in the past few seconds, going to try again ...");
                continue;
            } catch (IOException e) {
                Log.e(TAG, "Error while receiving TransportPacket:", e);
                continue;
            }

            final FindProtos.TransportPacket incomingPacket;
            try {
                incomingPacket = FindProtos.TransportPacket.parseFrom(
                        Arrays.copyOf(udpPacket.getData(), udpPacket.getLength()));
            } catch (InvalidProtocolBufferException e) {
                // Not a TransportPacket, skip
                continue;
            }

            // calculate packet queues to put the incoming packet into
            final List<FullContract.PacketQueues> queue = new ArrayList<>();

            final boolean isReceiver = (incomingPacket.hasTargetNode() && Arrays.equals(
                    incomingPacket.getTargetNode().toByteArray(), mIdentity.getPublicKey()));

            final ByteBuffer protocol = incomingPacket.getProtocol().asReadOnlyByteBuffer();
            final boolean supportedProtocol = mProtocolRegistry.hasProtocolImplementations(protocol);

            if (isReceiver) {
                if (!supportedProtocol) {
                    // The packet is targeted at us, but there is no client app installed
                    // which implements the protocol (otherwise, the 'if' clause would have
                    // consumed the packet already) - reject this packet.
                    Log.v(TAG, "Rejecting incoming packet");
                    continue;
                }

                Log.v(TAG, "Adding incoming packet (targeted) to INCOMING queue");
                queue.add(FullContract.PacketQueues.INCOMING);
            } else {
                // No target node always means "FORWARDING"
                Log.v(TAG, "Adding incoming packet to FORWARDING queue");
                queue.add(FullContract.PacketQueues.FORWARDING);

                if (!incomingPacket.hasTargetNode() && supportedProtocol) {
                    Log.v(TAG, "Adding incoming packet (untargeted) to INCOMING queue");
                    queue.add(FullContract.PacketQueues.INCOMING);
                }
            }

            mPacketRegistry.registerIncomingPacket(incomingPacket, queue.toArray(new FullContract.PacketQueues[] {}));
        }

        socket.close();
    }
}
