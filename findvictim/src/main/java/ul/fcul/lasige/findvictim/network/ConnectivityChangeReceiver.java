package ul.fcul.lasige.findvictim.network;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.util.Log;

import ul.fcul.lasige.find.lib.service.FindConnector;
import ul.fcul.lasige.findvictim.sensors.SensorsService;
import ul.fcul.lasige.findvictim.utils.NetworkUtils;

/**
 * Created by hugonicolau on 04/12/15.
 */
public class ConnectivityChangeReceiver extends BroadcastReceiver {
    private static final String TAG = ConnectivityChangeReceiver.class.getSimpleName();

    private SensorsService mService = null;

    public ConnectivityChangeReceiver(SensorsService service) {
        mService = service;
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        Log.d(TAG, "Connectivity changed");
        if(NetworkUtils.isOnline(context));
            mService.sync();
    }

}
