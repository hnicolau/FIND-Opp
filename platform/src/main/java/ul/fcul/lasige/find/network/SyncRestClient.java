package ul.fcul.lasige.find.network;

import android.content.Context;

import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;
import com.loopj.android.http.SyncHttpClient;

import cz.msebera.android.httpclient.entity.ByteArrayEntity;
import cz.msebera.android.httpclient.entity.StringEntity;

/**
 * Created by hugonicolau on 30/11/15.
 */
public class SyncRestClient {


    private static SyncHttpClient client = new SyncHttpClient();

    public static void get(String url, AsyncHttpResponseHandler responseHandler) {
        client.get(url, responseHandler);
    }

    public static void get(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        client.get(url, params, responseHandler);
    }

    public static void post(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        client.post(url, params, responseHandler);
    }

    public static void post(Context context, String url, StringEntity entity, AsyncHttpResponseHandler responseHandler) {
        client.post(context, url, entity, "application/json", responseHandler);
    }
}
