package ul.fcul.lasige.findvictim.webservices;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
import ul.fcul.lasige.findvictim.utils.ServerUtils;

/**
 * Created by hugonicolau on 30/11/15.
 */
public class RequestServer {
    private static final String TAG = RequestServer.class.getSimpleName();
    public static String ACTION_PACKETS_SENT = "ul.fcul.lasige.findvictim.action.packetssent";
    public static String EXTRA_PACKETS_SENT = "ul.fcul.lasige.findvictim.extra.packetssent";

    public static void register(final Context context, final String locale, final String mac, final String email, final String token) {

        // get endpoint for webservice depending on current country
        String method = "json";
        // get country
        SyncIpApiClient.get(method, new RequestParams(), new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                Log.d(TAG, "Success on get IP (JSONObject): " + response.toString());

                try {
                    String country = response.getString("country");
                    Log.d(TAG, "Country: " + country);

                    // get endpoint for country
                    getEndPoint(country, context, locale, mac, email, token);

                } catch (JSONException e) {
                    e.printStackTrace();
                    registerOnServer(context, null, locale, mac, email, token, "");
                }
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                Log.d(TAG, "Failure on get IP code: " + statusCode + "response: " + errorResponse);
                registerOnServer(context, null, locale, mac, email, token, "");
            }
        });
    }

    private static void getEndPoint(String country, final Context context, final String locale, final String mac, final String email, final String token) {
        String method = "server/location/" + country;

        SyncFindRestClient.get(method, new JsonHttpResponseHandler() {

            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONArray jsonArray) {
                try {
                    Log.d(TAG, "Success on get endpoint, code: " + statusCode + ", response: " + jsonArray.get(0).toString());
                    if(jsonArray.length() > 0) {
                        JSONObject obj = jsonArray.getJSONObject(0);
                        String endpoint = obj.getString("url");
                        String mode = "";
                        if(obj.has("mode")) mode = obj.getString("mode");

                        registerOnServer(context, endpoint, locale, mac, email, token, mode);
                    }
                    else {
                        registerOnServer(context, null, locale, mac, email, token, "");
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                    registerOnServer(context, null, locale, mac, email, token, "");
                }
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, Throwable error, JSONArray jsonArray) {
                Log.d(TAG, "Failure on get endpoint, code: " + statusCode + ", response: " + jsonArray);
                registerOnServer(context, null, locale, mac, email, token, "");
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, String str, Throwable throwable) {
                Log.d(TAG, "Failure on get endpoint, code: " + statusCode + ", response: " + str);
                registerOnServer(context, null, locale, mac, email, token, "");
            }
        });
    }

    private static void registerOnServer(final Context context, final String endpoint, final String locale, final String mac,
                                         final String email, final String token, final String mode) {
        Log.d(TAG, "Going to register on " + endpoint + " with " + mac + ", " + email + ", " + mode);
        // register on server
        String method = "server/register";

        // configure endpoint
        if(endpoint != null) SyncFindRestClient.setCustomEndPoint(endpoint);

        // configure REST params
        JSONObject jsonParams = new JSONObject();
        try {
            jsonParams.put("regId", token);
            jsonParams.put("mac", mac);
            jsonParams.put("email", email);
            jsonParams.put("mode", mode); // legacy code
            StringEntity entity = new StringEntity(jsonParams.toString());

            SyncFindRestClient.post(context, method, entity, new JsonHttpResponseHandler() {

                @Override
                public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                    Log.d(TAG, "Success on register (JSONObject): " + response.toString());

                    // save registration details
                    TokenStore.saveRegistration(context, locale, mac, email, token);
                    notifyUI(context);
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                    Log.d(TAG, "Failure on register (JSONObject) code: " + statusCode + " response: " + errorResponse);

                    TokenStore.deleteRegistration(context);
                    notifyUI(context);
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONArray errorResponse) {
                    Log.d(TAG, "Failure on register (JSONArray) code: " + statusCode + " response: " + errorResponse);

                    TokenStore.deleteRegistration(context);
                    notifyUI(context);
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, String str, Throwable throwable) {
                    Log.d(TAG, "Failure on register (String) code: " + statusCode + " response: " + str);

                    TokenStore.deleteRegistration(context);
                    notifyUI(context);
                }
            });
        } catch (JSONException e) {
            e.printStackTrace();
            TokenStore.deleteRegistration(context);
            notifyUI(context);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            TokenStore.deleteRegistration(context);
            notifyUI(context);
        }
    }

    public static void sendPackets(final Context context, ArrayList<Message> messages) {
        Log.d(TAG, "Going to send packets from: " + TokenStore.getMacAddress(context));
        String method = "victims";
        // build web service data - JSON
        JSONArray jsonArray = new JSONArray();
        for(Message message : messages) {
            JSONObject jsonMessage = ServerUtils.buildJSONFromMessage(context, message);
            jsonArray.put(jsonMessage);
        }

        try {
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

    private static void notifyUI(Context context) {
        // Notify UI that registration has finished
        Intent registrationComplete = new Intent(TokenStore.KEY_REGISTRATION_COMPLETE);
        LocalBroadcastManager.getInstance(context).sendBroadcast(registrationComplete);
    }
}
