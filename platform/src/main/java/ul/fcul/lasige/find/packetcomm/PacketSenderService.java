package ul.fcul.lasige.find.packetcomm;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceActivity;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.loopj.android.http.JsonHttpResponseHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.entity.ByteArrayEntity;
import cz.msebera.android.httpclient.entity.StringEntity;
import ul.fcul.lasige.find.apps.ProtocolRegistry;
import ul.fcul.lasige.find.beaconing.BeaconingManager;
import ul.fcul.lasige.find.crypto.CryptoHelper;
import ul.fcul.lasige.find.data.ClientImplementation;
import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.network.SyncRestClient;
import ul.fcul.lasige.find.protocolbuffer.FindProtos;

/**
 * Service that extends from {@link IntentService} and sends a given packet to a given neighbor.
 * <p/>
 * Created by hugonicolau on 17/11/15.
 */
public class PacketSenderService extends IntentService {
    private static final String TAG = PacketSenderService.class.getSimpleName();

    // receiving port
    public static final int PACKET_RECEIVING_PORT = 3109;

    // action to send packet
    private static final String ACTION_SEND_PACKET = "ul.fcul.lasige.find.action.SEND_PACKET";
    // action to sync the packet to the endpoint
    public static final String ACTION_INTERNET_SYNC = "ul.fcul.lasige.find.action.SYNC_PACKET";
    // extra in sending packet intent - neighbor id
    private static final String EXTRA_NEIGHBOR_ID = "ul.fcul.lasige.find.extra.NEIGHBOR_ID";
    // extra in sending packet intent - packet id
    private static final String EXTRA_PACKETS_LIST = "ul.fcul.lasige.find.extra.PACKETS_LIST";
    // extra in sending packet intent - packet endpoint
    private static final String EXTRA_PACKET_ENDPOINT = "ul.fcul.lasige.find.extra.PACKET_ENDPOINT";

    // database controller
    private DbController mDbController;
    // socket used by service
    private DatagramSocket mSendSocket;
    // protocol registry to get access to client implementations (protocol - app)
    private ProtocolRegistry mProtocolRegistry;
    // beaconing manager to get access to wifi locks
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
        intent.putExtra(EXTRA_PACKETS_LIST, packetId);
        context.startService(intent);
    }

    /**
     * Starts this service to perform an action with the given parameters. If the service is
     * already performing a task this action will be queued.
     *
     * @see IntentService
     */
    public static void startSendPacketInternet(Context context, String endpoint, JSONArray packetsData) {
        //TODO send all the packets data into one intent service to make a single post of all data
        Intent intent = new Intent(context, PacketSenderService.class);
        intent.setAction(ACTION_INTERNET_SYNC);
        intent.putExtra(EXTRA_PACKET_ENDPOINT, endpoint);
        intent.putExtra(EXTRA_PACKETS_LIST, packetsData.toString());
        context.startService(intent);


    }


    public PacketSenderService() throws SocketException {
        super(TAG);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // initializes variables and creates socket
        // socket is created only once, while packets exist in the queue
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
        // close socket
        if (mSendSocket != null) {
            Log.d(TAG, "closing packet socket");
            mSendSocket.close();
        }
        // releases wifi lock
        mBeaconingManager.resetWifiConnectionLock();
        super.onDestroy();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        // received a send action
        if (intent == null) {
            // not our intent.
            return;
        }

        if (intent.getAction().equals(ACTION_SEND_PACKET)) {
            //send ad-oc network
            sendPacketToNeighbours(intent);
        } else {
            //send to internet endpoint
            if (intent.getAction().equals(ACTION_INTERNET_SYNC)) {
                sendPacketToEndpoint(intent);
            }

        }


    }

    private void sendPacketToEndpoint(Intent intent) {
        String jsonArray = intent.getStringExtra(EXTRA_PACKETS_LIST);

        try {
            JSONArray packetData = new JSONArray(jsonArray);
            String endpoint = intent.getStringExtra(EXTRA_PACKET_ENDPOINT);
            if (packetData == null) {
                // not valid ids
                Log.e(TAG, "Invalid packet");
                return;
            }

            Log.d(TAG, "Sending to:" + endpoint);
            StringEntity entity = new StringEntity(packetData.toString());

            SyncRestClient.post(getApplicationContext(), endpoint, entity, new JsonHttpResponseHandler() {

                @Override
                public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                    Log.d(TAG, "Success on uploading victim information (JSONObject): " + response.toString());
                    //notifyPacketsSent(context, true);
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                    Log.d(TAG, "Failure on uploading victim information (JSONObject) code: " + statusCode + " response: " + errorResponse);
                    //notifyPacketsSent(context, false);
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, String str, Throwable throwable) {
                    Log.d(TAG, "Failure on register (String) code: " + statusCode + " response: " + str);
                    //notifyPacketsSent(context, false);
                }
            });

        } catch (JSONException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    private void sendPacketToNeighbours(Intent intent) {
        // get neighbor and packet ids from intent
        final long neighborId = intent.getLongExtra(EXTRA_NEIGHBOR_ID, -1);
        final long packetId = intent.getLongExtra(EXTRA_PACKETS_LIST, -1);

        if (neighborId <= 0 || packetId <= 0) {
            // not valid ids
            Log.e(TAG, "Invalid neighbor/packet ID: " + neighborId + "/" + packetId);
            // release lock
            mBeaconingManager.setWifiConnectionLocked(false);
            return;
        }

        // build packet
        final FindProtos.TransportPacket.Builder builder;
        try {
            // get packet from database
            builder = mDbController.getPacket(packetId);
        } catch (IllegalArgumentException e) {
            // packet does not exist anymore, skip it and release wifi lock
            Log.e(TAG, "Packet ID: " + packetId + " does not exist anymore, we will skip it");
            mBeaconingManager.setWifiConnectionLocked(false);
            return;
        }

        // get neighbor from database
        Neighbor neighbor = mDbController.getNeighbor(neighborId);
        if (!neighbor.hasLastSeenNetwork()) {
            // no wifi connection
            // TODO: Data exchange is currently only supported over Wifi
            Log.v(TAG, "neighbor is only reachable via bluetooth, we're not able to send packet");
            // release wifi lock
            mBeaconingManager.setWifiConnectionLocked(false);
            return;
        }

        Log.v(TAG, "Preparing packet " + packetId + " to be sent to neighbor " + neighbor);

        // encrypt and/or sign, if necessary
        Set<ClientImplementation> implementations = mProtocolRegistry.getProtocolImplementations(builder.getProtocol().toByteArray());

        // get protocol details
        if (!implementations.isEmpty()) {
            final ClientImplementation impl = implementations.iterator().next();

            // encrypt
            if (impl.isEncrypted()) {
                Log.v(TAG, "\tencrypting message...");
                final byte[] ciphertext = CryptoHelper.encrypt(this, builder.getData().toByteArray(), neighbor.getNodeId());
                builder.setData(ByteString.copyFrom(ciphertext));
            }

            // get our public key
            final byte[] nodeId = mDbController.getMasterIdentity().getPublicKey();
            if (impl.isSigned() && Arrays.equals(nodeId, builder.getSourceNode().toByteArray())) {
                // only change/set the MAC if we're not forwarding an already signed packet. we only sign our own packets
                Log.v(TAG, "\tsigning message...");
                // set mac - we use the mac field to sign packets
                builder.clearMac();
                final byte[] mac = CryptoHelper.sign(this, builder.buildPartial().toByteArray());
                builder.setMac(ByteString.copyFrom(mac));
            }
        }

        // TODO send packet to local apps? they may be listening for the same protocols ..

        // send packet to neighbor
        final byte[] packet = builder.build().toByteArray();
        // build datagram packet
        DatagramPacket rawPacket = new DatagramPacket(packet, packet.length, neighbor.getAnyIpAddress(), PACKET_RECEIVING_PORT);

        try {
            // send it!
            mSendSocket.send(rawPacket);
            Log.v(TAG, "\tsent " + packet.length + " bytes");

            // update last packet sent timestamp (this is used to be able to send only new packet next time)
            // make sure we are not updating the neighbor before older packets are sent
            // we only update time when there are no newer packets, otherwise the older packets wouldn't be sent
            if (mDbController.getOutgoingPackets(neighbor.getTimeLastPacket(),
                    mDbController.getPacketView(packetId).getTimeReceived()).getCount() == 0) {
                long now = System.currentTimeMillis() / 1000;
                mDbController.updateNeighborLastPacket(neighbor.getNodeId(), now);
            }
        } catch (IOException | NullPointerException e) {
            Log.e(TAG, "Error while sending packet " + packetId + " to neighbor " + neighborId, e);
        } finally {
            mBeaconingManager.setWifiConnectionLocked(false);
        }
    }
}
