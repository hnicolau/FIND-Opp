package com.example.unzi.findalert.webservice;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;

import com.loopj.android.http.JsonHttpResponseHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.entity.StringEntity;

/**
 * Created by unzi on 08/03/2016.
 */
public class WebLogging extends IntentService {
    private static final String TAG = WebLogging.class.getSimpleName();
    private static final String EXTRA_MESSAGE = "ul.fcul.lasige.findvictim.extra.MESSAGE";
    private static final String EXTRA_MAC = "ul.fcul.lasige.findvictim.extra.MAC";
    private static final String EXTRA_TYPE = "ul.fcul.lasige.findvictim.extra.TYPE";

    public WebLogging() {
        super(TAG);
    }


    /**
     * Starts this service with the given parameters. If the service is
     * already performing a task this action will be queued.
     *
     * @see IntentService
     */
    public static void logMessage(Context context, String message, String macAddress, String type) {
        Intent intent = new Intent(context, WebLogging.class);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_MAC, macAddress);
        intent.putExtra(EXTRA_TYPE, type);
        context.startService(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {

        // log message
        String method = "logger/log_message";

        final String message = intent.getStringExtra(EXTRA_MESSAGE);
        final String mac = intent.getStringExtra(EXTRA_MAC);
        final String type = intent.getStringExtra(EXTRA_TYPE);

        // configure REST params
        JSONObject jsonParams = new JSONObject();
        try {

            jsonParams.put("mac", mac);
            jsonParams.put("type", type);
            jsonParams.put("message", message);

            StringEntity entity = new StringEntity(jsonParams.toString());

            SyncFindRestClient.post(this, method, entity, new JsonHttpResponseHandler() {

                @Override
                public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONArray errorResponse) {
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, String str, Throwable throwable) {
                }
            });
        } catch (JSONException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }


}
