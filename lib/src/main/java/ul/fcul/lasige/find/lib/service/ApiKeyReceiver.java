package ul.fcul.lasige.find.lib.service;

import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import ul.fcul.lasige.find.lib.data.TokenStore;

/**
 * Created by hugonicolau on 03/11/2015.
 *
 * This class is responsible for requesting and receiving client app tokens. Both request and receive
 * are achieved via Broadcast.
 *
 */
public class ApiKeyReceiver extends BroadcastReceiver{
    private static final String TAG = ApiKeyReceiver.class.getSimpleName();

    // action used to request/receive app token
    public static final String ACTION_ISSUE_API_KEY = "ul.fcul.lasige.find.action.ISSUE_API_KEY";

    // parameters of broadcast intents
    public static final String EXTRA_APP_NAME = "ul.fcul.lasige.find.extra.APP_NAME";
    public static final String EXTRA_API_KEY = "ul.fcul.lasige.find.extra.API_KEY";

    /**
     * Sends an intent to the FIND platform requesting an API key for this FIND client. The
     * target application must hold the basic Find permission and have a broadcast receiver for
     * the ISSUE_API_KEY action defined in its manifest.
     *
     * @param context A context to start the target service in the FIND platform.
     * @see IntentService
     */
    public static void requestApiKey(Context context) {
        Log.d(TAG, "Going to broadcast ACTION_ISSUE_API_KEY");
        final Intent intent = new Intent(ACTION_ISSUE_API_KEY);
        intent.putExtra(EXTRA_APP_NAME, context.getPackageName());
        intent.setPackage(FindConnector.FIND_PACKAGE);
        context.startService(intent);
    }

    /**
     * Called when receiving a broadcast. The broadcast receiver for the ISSUE_API_KEY action is
     * defined in the manifest.
     *
     * @param context The context of the FIND client app.
     * @param intent Intent sent from the FIND service.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && intent.getAction().equals(ACTION_ISSUE_API_KEY)) {
            Log.d(TAG, "Received ACTION_ISSUE_API_KEY");
            // get token
            final String apiKey = intent.getStringExtra(EXTRA_API_KEY);
            if (apiKey != null) {
                // save token
                TokenStore.saveApiKey(context, apiKey);
            }
        }
    }
}
