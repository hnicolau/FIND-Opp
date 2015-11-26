package ul.fcul.lasige.find.packetcomm;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Set;

import ul.fcul.lasige.find.apps.ProtocolRegistry;
import ul.fcul.lasige.find.beaconing.BeaconingManager;
import ul.fcul.lasige.find.crypto.CryptoHelper;
import ul.fcul.lasige.find.data.ClientImplementation;
import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.protocolbuffer.FindProtos;

/**
 * Created by hugonicolau on 17/11/15.
 */
public class PacketSenderService extends IntentService {
    public static final int PACKET_RECEIVING_PORT = 3109;

    private static final String TAG = PacketSenderService.class.getSimpleName();

    private static final String ACTION_SEND_PACKET = "ul.fcul.lasige.find.action.SEND_PACKET";

    private static final String EXTRA_NEIGHBOR_ID = "ul.fcul.lasige.find.extra.NEIGHBOR_ID";
    private static final String EXTRA_PACKET_ID = "ul.fcul.lasige.find.extra.PACKET_ID";

    private DbController mDbController;
    private DatagramSocket mSendSocket;
    private ProtocolRegistry mProtocolRegistry;
    private BeaconingManager mBeaconingManager;

    /**
     * Starts this service to perform an action with the given parameters. If the service is
     * already performing a task this action will be queued.
     *
     * @see IntentService
     */
    public static void startSendPacket(Context context, long neighborId, long packetId) {
        Log.d(TAG, "startSendPacket: " + packetId);
        Intent intent = new Intent(context, PacketSenderService.class);
        intent.setAction(ACTION_SEND_PACKET);
        intent.putExtra(EXTRA_NEIGHBOR_ID, neighborId);
        intent.putExtra(EXTRA_PACKET_ID, packetId);
        context.startService(intent);
    }

    public PacketSenderService() throws SocketException {
        super(TAG);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mDbController = new DbController(getApplicationContext());
        try {
            Log.d(TAG, "creating packet socket");
            mSendSocket = new DatagramSocket();
        } catch (SocketException e) {
            throw new IllegalStateException("Couldn't create send socket", e);
        }
        mProtocolRegistry = ProtocolRegistry.getInstance(this);
        mBeaconingManager = BeaconingManager.getInstance(this);
    }

    @Override
    public void onDestroy() {
        if (mSendSocket != null) {
            Log.d(TAG, "closing packet socket");
            mSendSocket.close();
        }
        mBeaconingManager.resetWifiConnectionLock();
        super.onDestroy();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.v(TAG, "received action");
        if (intent == null || !intent.getAction().equals(ACTION_SEND_PACKET)) {
            // not our intent.
            return;
        }

        Log.v(TAG, "received action to send packet");

        final long neighborId = intent.getLongExtra(EXTRA_NEIGHBOR_ID, -1);
        final long packetId = intent.getLongExtra(EXTRA_PACKET_ID, -1);

        if (neighborId <= 0 || packetId <= 0) {
            Log.e(TAG, "Invalid neighbor/packet ID: " + neighborId + "/" + packetId);
            mBeaconingManager.setWifiConnectionLocked(false);
            return;
        }

        // build packet
        final FindProtos.TransportPacket.Builder builder;
        try {
            builder = mDbController.getPacket(packetId);
        } catch (IllegalArgumentException e) {
            // packet does not exist anymore, skip it.
            Log.e(TAG, "Packet ID: " + packetId + " does not exist anymore, we will skip it");
            mBeaconingManager.setWifiConnectionLocked(false);
            return;
        }

        Neighbor neighbor = mDbController.getNeighbor(neighborId);
        if (!neighbor.hasLastSeenNetwork()) {
            // TODO: Data exchange is currently only supported over Wifi
            Log.v(TAG, "neighbor is only reachable via bluetooth, we're not able to send packet");
            mBeaconingManager.setWifiConnectionLocked(false);
            return;
        }

        Log.v(TAG, "Preparing packet " + packetId + " to be sent to neighbor " + neighbor);

        // Encrypt and/or sign, if necessary
        Set<ClientImplementation> implementations = mProtocolRegistry.getProtocolImplementations(builder.getProtocol().toByteArray());

        // get protocol details
        if (!implementations.isEmpty()) {
            final ClientImplementation impl = implementations.iterator().next();

            // encrypt
            if (impl.isEncrypted()) {
                Log.v(TAG, "\tencrypting message...");
                final byte[] ciphertext = CryptoHelper.encrypt(
                        this, builder.getData().toByteArray(), neighbor.getNodeId());
                builder.setData(ByteString.copyFrom(ciphertext));
            }

            final byte[] nodeId = mDbController.getMasterIdentity().getPublicKey();
            if (impl.isSigned() && Arrays.equals(nodeId, builder.getSourceNode().toByteArray())) {
                // only change/set the MAC if we're not forwarding an already signed packet
                Log.v(TAG, "\tsigning message...");
                builder.clearMac();
                final byte[] mac = CryptoHelper.sign(this, builder.buildPartial().toByteArray());
                builder.setMac(ByteString.copyFrom(mac));
            }
        }

        // TODO send packet to local apps?
        // Send packet to neighbor
        final byte[] packet = builder.build().toByteArray();
        DatagramPacket rawPacket = new DatagramPacket(
                packet, packet.length, neighbor.getAnyIpAddress(), PACKET_RECEIVING_PORT);

        try {
            mSendSocket.send(rawPacket);
            Log.v(TAG, "\tsent " + packet.length + " bytes");

            // update last packet sent timestamp
            // make sure we are not updating the neighbor before other packets are sent
            if (mDbController.getOutgoingPackets(neighbor.getTimeLastPacket(),
                    mDbController.getPacketView(packetId).getTimeReceived()).getCount() == 0) {
                long now = System.currentTimeMillis() / 1000;
                mDbController.updateNeighborLastPacket(neighbor.getNodeId(), now);
                Log.d(TAG, "updated lastPacket timestamp to: " + now);
            }
            else
            {
                Log.d(TAG, " ---------------- lastPacket: NOT UPDATED!!");
            }

        } catch (IOException e) {
            Log.e(TAG, "Error while sending packet " + packetId + " to neighbor " + neighborId, e);
        } finally {
            mBeaconingManager.setWifiConnectionLocked(false);
        }
    }
}
