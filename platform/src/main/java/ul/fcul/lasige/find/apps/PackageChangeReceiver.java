package ul.fcul.lasige.find.apps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * Class extends from {@link BroadcastReceiver} and listens for new app installs or uninstalls.
 * Registration for those actions is done statically (in manifest).
 *
 * @see BroadcastReceiver
 * Created by hugonicolau on 04/11/2015.
 */
public class PackageChangeReceiver extends BroadcastReceiver {
    private static final String TAG = PackageChangeReceiver.class.getSimpleName();

    /**
     * Constructor
     */
    public PackageChangeReceiver() {}

    /**
     * Receives broadcasts and handles {@link Intent#ACTION_PACKAGE_ADDED ACTION_PACKAGE_ADDED} and
     * {@link Intent#ACTION_PACKAGE_REMOVED ACTION_PACKAGE_REMOVED} actions.
     *
     * <p>In {@link Intent#ACTION_PACKAGE_ADDED ACTION_PACKAGE_ADDED} it issues a new API key and broadcasts
     * it to client app.</p>
     * <p>In {@link Intent#ACTION_PACKAGE_REMOVED ACTION_PACKAGE_REMOVED} it revokes an existing API key.</p>
     *
     * @param context Application context.
     * @param intent Broadcast's intent.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            final Uri data = intent.getData();
            final String appName = data.getEncodedSchemeSpecificPart();
            final Bundle extras = intent.getExtras();

            switch (action) {
                case Intent.ACTION_PACKAGE_ADDED: {
                    Log.d(TAG, "package installed, going to issue apiKey for " + appName);
                    if (extras != null && !extras.getBoolean(Intent.EXTRA_REPLACING)) {
                        AppRegistrationService.startIssueApiKey(context, appName);
                    }
                    break;
                }

                case Intent.ACTION_PACKAGE_REMOVED: {
                    Log.d(TAG, "package removed, going to revoke apiKey for " + appName);
                    if (extras != null && !extras.getBoolean(Intent.EXTRA_REPLACING)) {
                        AppRegistrationService.startRevokeApiKey(context, appName);
                    }
                    break;
                }

                default:
                    // Not a broadcast we can handle
                    break;
            }
        }
    }
}
