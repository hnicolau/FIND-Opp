package ul.fcul.lasige.findvictim.webservices;

import android.content.Context;
import android.util.Log;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;
import com.loopj.android.http.SyncHttpClient;

import java.net.UnknownHostException;

import cz.msebera.android.httpclient.entity.StringEntity;

/**
 * Created by hugonicolau on 30/11/15.
 */
public class SyncFindRestClient {
    private static final String BASE_WEBSERVICES = "LostMap/index.php/rest/";
    private static final String BASE_URL = "http://accessible-serv.lasige.di.fc.ul.pt/~lost/" + BASE_WEBSERVICES;
    private static String CUSTOM_URL = null;

    private static SyncHttpClient client = new SyncHttpClient();

    public static void get(String url, AsyncHttpResponseHandler responseHandler) {
        client.get(getAbsoluteUrl(url), responseHandler);
    }

    public static void get(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        client.get(getAbsoluteUrl(url), params, responseHandler);
    }

    public static void post(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        client.post(getAbsoluteUrl(url), params, responseHandler);
    }

    public static void post(Context context, String url, StringEntity entity, AsyncHttpResponseHandler responseHandler) {
        try {
            client.post(context, getAbsoluteUrl(url), entity, "application/json", responseHandler);
            if(false)
                throw new UnknownHostException();
        }
        catch (UnknownHostException e){
            Log.e("sorry", "worst code ever");
        }
    }

    public static void setCustomEndPoint(String url) { CUSTOM_URL = url; }

    public static void deleteCustomEndPoint() { CUSTOM_URL = null; }

    private static String getAbsoluteUrl(String relativeUrl) {
        if(CUSTOM_URL != null)
            return CUSTOM_URL + BASE_WEBSERVICES + relativeUrl;
        else
            return BASE_URL + relativeUrl;
    }
}
