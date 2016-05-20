package ul.fcul.lasige.findvictim.webservices;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Base64;
import android.util.Log;

import com.example.unzi.offlinemaps.DownloadFile;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.client.HttpClient;
import cz.msebera.android.httpclient.entity.StringEntity;
import cz.msebera.android.httpclient.impl.client.DefaultHttpClient;
import ul.fcul.lasige.find.lib.data.Packet;
import ul.fcul.lasige.findvictim.data.Message;
import ul.fcul.lasige.findvictim.data.TokenStore;
import ul.fcul.lasige.findvictim.network.ConnectivityChangeReceiver;
import ul.fcul.lasige.findvictim.utils.DeviceUtils;

/**
 * Created by hugonicolau on 30/11/15.
 */
public class RequestServer {
    private static final String TAG = RequestServer.class.getSimpleName();
    public static String ACTION_PACKETS_SENT = "ul.fcul.lasige.findvictim.action.packetssent";
    public static String EXTRA_PACKETS_SENT = "ul.fcul.lasige.findvictim.extra.packetssent";


    //send messages
    public static void sendPackets(final Context context, String [] messages) {

        String method = "victims";

        //build json array from packets
        JSONArray jsonArray = new JSONArray();
        for(String message : messages) {
            jsonArray.put(Message.createJSONByteData(message));
        }


        try {
            JSONObject senderMac = new JSONObject();
            senderMac.put("syncMacAddress", DeviceUtils.getWifiMacAddress());
            senderMac.put("type", "sync");
            jsonArray.put(senderMac);

            StringEntity entity = new StringEntity(jsonArray.toString());

            SyncFindRestClient.post(context, method, entity, new JsonHttpResponseHandler() {

                @Override
                public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                    Log.d(TAG, "Success on uploading victim information (JSONObject): " + response.toString());
                    notifyPacketsSent(context, true);
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                    Log.d(TAG, "Failure on uploading victim information (JSONObject) code: " + statusCode + " response: " + errorResponse);
                    notifyPacketsSent(context, false);
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, String str, Throwable throwable) {
                    Log.d(TAG, "Failure on register (String) code: " + statusCode + " response: " + str);
                    notifyPacketsSent(context, false);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            notifyPacketsSent(context, false);
        }
    }

    private static void notifyPacketsSent(Context context, boolean success) {
        // notify that we finished trying sending packets
        Log.d(TAG, "Notifying UI of packets sent");
        Intent packetsSent = new Intent(ACTION_PACKETS_SENT);
        packetsSent.putExtra(EXTRA_PACKETS_SENT, success);
        LocalBroadcastManager.getInstance(context).sendBroadcast(packetsSent);
    }

}
