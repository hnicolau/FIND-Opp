package ul.fcul.lasige.findvictim.webservices;

import android.content.Context;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import cz.msebera.android.httpclient.entity.StringEntity;

/**
 * Created by hugonicolau on 09/10/15.
 */
public class AsyncFindRestClient {
    private static final String BASE_URL = "http://accessible-serv.lasige.di.fc.ul.pt/~lost/LostMap/index.php/rest/";
    private static String CUSTOM_URL = null;

    private static AsyncHttpClient client = new AsyncHttpClient();

    public static void get(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        client.get(getAbsoluteUrl(url), params, responseHandler);
    }

    public static void post(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        client.post(getAbsoluteUrl(url), params, responseHandler);
    }

    public static void post(Context context, String url, StringEntity entity, AsyncHttpResponseHandler responseHandler) {
        //client.post(getAbsoluteUrl(url), params, responseHandler);
        client.post(context, url, entity, "application/json", responseHandler);
    }

    public static void setCustomEndPoint(String url) { CUSTOM_URL = url; }

    public static void deleteCustomEndPoint() { CUSTOM_URL = null; }

    private static String getAbsoluteUrl(String relativeUrl) {
        if(CUSTOM_URL != null)
            return CUSTOM_URL + relativeUrl;
        else
            return BASE_URL + relativeUrl;
    }
}
