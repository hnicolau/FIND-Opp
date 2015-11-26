package ul.fcul.lasige.find.apps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * Created by hugonicolau on 04/11/2015.
 */
public class PackageChangeReceiver extends BroadcastReceiver {
    private static final String TAG = PackageChangeReceiver.class.getSimpleName();

    public PackageChangeReceiver() {}

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            final Uri data = intent.getData();
            final String appName = data.getEncodedSchemeSpecificPart();
            final Bundle extras = intent.getExtras();

            switch (action) {
                case Intent.ACTION_PACKAGE_ADDED: {
                    Log.d(TAG, "package installed, going to issue apiKey");
                    if (extras != null && !extras.getBoolean(Intent.EXTRA_REPLACING)) {
                        AppRegistrationService.startIssueApiKey(context, appName);
                    }
                    break;
                }

                case Intent.ACTION_PACKAGE_REMOVED: {
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
