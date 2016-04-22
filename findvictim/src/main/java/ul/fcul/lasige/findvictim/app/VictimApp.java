package ul.fcul.lasige.findvictim.app;

import android.app.AlertDialog;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.LocationManager;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import ul.fcul.lasige.findvictim.sensors.SensorsService;

/**
 * Created by hugonicolau on 11/12/15.
 */
public class VictimApp extends Application {
    private static final String TAG = VictimApp.class.getSimpleName();

    // sensor service
    private ServiceConnection mSensorsConnection;
    private SensorsService mSensors = null;

    @Override
    public void onCreate() {
        Log.d(TAG, "Victim APP: CREATED");

        // bind to sensors service
        mSensorsConnection = new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mSensors = null;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                final SensorsService.SensorsBinder binder = (SensorsService.SensorsBinder) service;
                mSensors = binder.getSensors();
            }
        };
        SensorsService.bindSensorsService(getApplicationContext(), mSensorsConnection);

        super.onCreate();
    }

    public boolean starSensors() {
        if(mSensors != null) {
            Log.d(TAG, "activating sensors");
            mSensors.activateSensors(false);
            return true;
        }
        else {
            Log.d(TAG, "couldn't activate sensors");
            return false;
        }
    }

    public boolean stopSensors() {
        if(mSensors != null) {
            Log.d(TAG, "deactivating sensors");
            mSensors.deactivateSensors();
            return true;
        }
        else {
            Log.d(TAG, "couldn't deactivate sensors");
            return false;
        }
    }

}
