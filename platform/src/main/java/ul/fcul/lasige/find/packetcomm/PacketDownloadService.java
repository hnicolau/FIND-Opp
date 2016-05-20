package ul.fcul.lasige.find.packetcomm;

import android.app.IntentService;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Base64;
import android.util.Log;

import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.DatagramSocket;
import java.net.SocketException;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.entity.StringEntity;
import ul.fcul.lasige.find.apps.ProtocolRegistry;
import ul.fcul.lasige.find.beaconing.BeaconingManager;
import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.lib.data.FindContract;
import ul.fcul.lasige.find.network.SyncRestClient;
import ul.fcul.lasige.find.service.SynchronizedPackets;

/**
 * Created by unzi on 23/03/2016.
 */
public class PacketDownloadService  extends IntentService {
    private static final String TAG = PacketDownloadService.class.getSimpleName();

    // action to receive packets from endpoint
    private static final String ACTION_INTERNET_DOWNLOAD = "ul.fcul.lasige.find.action.DOWNLOAD_PACKETS";
    // extra in download endpoint
    private static final String EXTRA_DOWNLOAD_ENDPOINT = "ul.fcul.lasige.find.extra.DOWNLOAD_ENDPOINT";
    // update the last time we downloaded packets
    private static final String LAST_DOWNLOAD = "LAST_DOWNLOAD";
    // last update to the endpoint
    private static final String EXTRA_NEW_SYNC_TIME = "ul.fcul.lasige.find.extra.NEW_SYNC_TIMESTAMP";
    //protocol hash
    private static final String EXTRA_PROTOCOL_HASH = "ul.fcul.lasige.find.extra.PROTOCOL_HASH";
    //protocol default TTL
    private static final String EXTRA_TTL = "ul.fcul.lasige.find.extra.TTL";


    // database controller
    private DbController mDbController;
    // socket used by service
    private DatagramSocket mSendSocket;
    // protocol registry to get access to client implementations (protocol - app)
    private ProtocolRegistry mProtocolRegistry;
    // beaconing manager to get access to wifi locks
    private BeaconingManager mBeaconingManager;

    private  Context mContext;

    public PacketDownloadService() throws SocketException {
        super(TAG);
    }

    public static void startGetPacketInternet(Context context, String downloadEndpoint, long newUpdateTime, byte [] protocolhash, long ttl) {
        Log.d(TAG, "startDownloadPacket: " + downloadEndpoint);
        Intent intent = new Intent(context, PacketDownloadService.class);
        intent.setAction(ACTION_INTERNET_DOWNLOAD);
        intent.putExtra(EXTRA_DOWNLOAD_ENDPOINT, downloadEndpoint);
        intent.putExtra(EXTRA_NEW_SYNC_TIME, newUpdateTime);
        intent.putExtra(EXTRA_PROTOCOL_HASH, protocolhash);
        intent.putExtra(EXTRA_TTL, ttl);

        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // initializes variables and creates socket
        // socket is created only once, while packets exist in the queue
        mDbController = new DbController(getApplicationContext());
        try {
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
        if (intent.getAction().equals(ACTION_INTERNET_DOWNLOAD)) {
            mContext = this;
            //send ad-oc network
            downloadFromEndpoint(intent);
        }
    }

    private void downloadFromEndpoint(Intent intent) {
        final long newSyncTime = intent.getLongExtra(EXTRA_NEW_SYNC_TIME, 0);
        final String downloadEndpoint = intent.getStringExtra(EXTRA_DOWNLOAD_ENDPOINT);
        final byte []  protocolHash = intent.getByteArrayExtra(EXTRA_PROTOCOL_HASH);
        final long ttl = intent.getLongExtra(EXTRA_TTL, 0);

        //getting time of the last download
        //TODO have the time of the download per protocol instead of 1
        RequestParams params = new RequestParams();
        params.put(LAST_DOWNLOAD, SynchronizedPackets.lastDownloadedSuccess(getApplicationContext()));
            SyncRestClient.get( downloadEndpoint,params, new JsonHttpResponseHandler() {

                @Override
                public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                    try {
                        // Receiving side
                        String text = response.getString("data");
                        Log.d(TAG, "Success on downloading victim information (objetc): " + text);
                        PacketRegistry.getInstance(mContext).registerDownloadedPacket(text.getBytes("UTF-8"), protocolHash, ttl);
                        SynchronizedPackets.packetsSuccessfullyDownloaded(getApplicationContext(), newSyncTime);

                    } catch (JSONException e) {
                        e.printStackTrace();
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                    finally {
                        BeaconingManager.getInstance(getApplicationContext()).releaseInternetLock(downloadEndpoint);
                    }
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                    Log.d(TAG, "Failure on downloading victim information (jsonArray) code: " + statusCode + " response: " + errorResponse);
                    BeaconingManager.getInstance(getApplicationContext()).releaseInternetLock(downloadEndpoint);
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, String str, Throwable throwable) {
                    Log.d(TAG, "Failure on downloading (String) code: " + statusCode + " response: " + str);
                    BeaconingManager.getInstance(getApplicationContext()).releaseInternetLock(downloadEndpoint);
                }
            });


    }
}
