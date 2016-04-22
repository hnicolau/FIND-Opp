package ul.fcul.lasige.findvictim.sensors;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Updates geographical location positioning data. It uses
 * {@link android.content.SharedPreferences SharedPreferences} to store the
 * value temporally and allow shared access to other components.
 * <p/>
 * The Location Provider uses the device's GPS to get the current location. Each
 * location has an associated confidence level
 *
 * @author André Silva <asilva@lasige.di.fc.ul.pt>
 */

public class LocationSensor extends AbstractSensor {
    private static final String TAG = LocationSensor.class.getSimpleName();

    private static final int INITIAL_INTERVAL = 1 * 1000; // 1 sec
    private static final int SUBSEQUENT_INTERVAL = 2 * 60 * 1000; // 2 minutes
    private static final int DISTANCE = 5; // meters

    private int currentInterval;
    private boolean changedInterval;
    private String currentProvider;

    private Context context;
    private LocationManager mLocManager;
    private Handler handler;

    private Location currentBestLocation;

    private LocationListener locationListener = new LocationListener() {

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.i(TAG, "Status change: #status=" + status + " for " + provider);
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.i(TAG, "Provider enabled: " + provider);
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.i(TAG, "Provider disabled: " + provider);
        }

        @Override
        public void onLocationChanged(Location location) {

            if (isBetterLocation(location, currentBestLocation)) {
                currentBestLocation = location;

                Log.i(TAG,
                        "Location Changed. Updated by "
                                + location.getProvider() + " provider.");

                Log.i(TAG, "Latitude is " + location.getLatitude()
                        + ". Longitude is " + location.getLongitude());

                if (currentInterval == INITIAL_INTERVAL) {
                    // first location found. Set less frequent updates
                    currentInterval = SUBSEQUENT_INTERVAL;
                    changedInterval = true;
                }
            }
        }
    };

    /**
     * Determines whether one Location reading is better than the current
     * Location fix
     *
     * @param newLocation         The new Location that to evaluate
     * @param currentBestLocation The current Location fix, to compare the new one
     */
    private static boolean isBetterLocation(Location newLocation,
                                            Location currentBestLocation) {

        if (currentBestLocation.getLatitude() == 0
                && currentBestLocation.getLongitude() == 0
                && newLocation.getLatitude() != 0
                && newLocation.getLongitude() != 0) {

            // A new location is always better than no location
            return true;
        }

        // check if the new location fix is more accurate
        if (newLocation.getAccuracy() < currentBestLocation.getAccuracy()) {
            return true;
        }

        double distance = newLocation.distanceTo(currentBestLocation);

        // if the currentBestLocation fix (which is more accurate) is completely
        // inside the newLocation, then the later is not a better location
        return !(distance + currentBestLocation.getAccuracy() <= newLocation
                .getAccuracy());
    }

    /**
     * Creates a new LocationSensor to gather geographical location updates
     *
     * @param c Android context
     */
    public LocationSensor(Context c) {
        super(c);
        context = c;

        mLocManager = (LocationManager) context
                .getSystemService(Context.LOCATION_SERVICE);

        handler = new Handler();
        currentBestLocation = new Location(""); // latitude and longitude are zero by default
        currentInterval = INITIAL_INTERVAL;
    }

    @Override
    public void startSensor() {
        currentProvider = getBestProvider();
        Log.i(TAG, "Chosen provider: " + currentProvider);
        registerLocationListeners(locationListener);
        handler.postDelayed(mRunnable, currentInterval);
    }

    @Override
    public Object getCurrentValue() {
        return currentBestLocation;
    }

    @Override
    public void stopSensor() {
        handler.removeCallbacks(mRunnable);
        unregisterLocationListener(locationListener);
    }

    /**
     * Registers an event listener to get GPS/wifi updates
     *
     * @param locListener location listener to receive coordinate updates
     */
    private void registerLocationListeners(LocationListener locListener) {

        unregisterLocationListener(locListener);
        try {

            mLocManager.requestLocationUpdates(currentProvider, currentInterval,
                    DISTANCE, locListener);
        } catch (SecurityException e) {
            Log.e(TAG, "Location services permissions are not enabled!");
        }
    }

    private String getBestProvider() {
        Criteria myCriteria = new Criteria();
        myCriteria.setAccuracy(Criteria.ACCURACY_LOW);
        myCriteria.setPowerRequirement(Criteria.POWER_LOW);

        return mLocManager.getBestProvider(myCriteria, true);
    }

    /**
     * Unregisters a previously registered location listener
     *
     * @param locListener location listener to remove
     */
    private void unregisterLocationListener(LocationListener locListener) {
        try {
            mLocManager.removeUpdates(locListener);
        } catch (SecurityException e) {
            Log.e(TAG, "Location services permissions are not enabled!");
        }

    }

    /**
     * Checks if current provider is still enabled. If not, then tries to change
     * provider
     */
    private Runnable mRunnable = new Runnable() {

        @Override
        public void run() {
            //Log.i(TAG, "run");

            if (betterConnectionAvailable()) {
                // change provider
                currentProvider = currentProvider
                        .equals(LocationManager.GPS_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                        : LocationManager.GPS_PROVIDER;
                Log.i(TAG, "Chosen provider: " + currentProvider);
                registerLocationListeners(locationListener);
            } else if (changedInterval) {
                // register again for the changes to take effect
                registerLocationListeners(locationListener);
                changedInterval = false;
                Log.i(TAG, "changed interval");
            }
            handler.postDelayed(mRunnable, currentInterval);
        }

    };

    private boolean betterConnectionAvailable() {
        // change from gps to network provider even if ap is enabled
        if (currentProvider.equals(LocationManager.GPS_PROVIDER)
                && (isWifiEnabled() || isAPEnabled())) {
            return true;
        } else if (currentProvider.equals(LocationManager.NETWORK_PROVIDER)
                && !isWifiEnabled() && !isAPEnabled()) {
            return true;
        }
        return false;
    }

    private boolean isWifiEnabled() {
        // TODO: check if it really has Internet access

        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        return ni.isConnected();
    }

    private boolean isAPEnabled() {
        boolean apEnabled = false;

        WifiManager manager = (WifiManager) context
                .getSystemService(Context.WIFI_SERVICE);

        try {
            final Method method = manager.getClass().getDeclaredMethod(
                    "isWifiApEnabled");
            method.setAccessible(true);
            apEnabled = (Boolean) method.invoke(manager);

        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "Could not check if AP is enabled.");
        }

        return apEnabled;
    }
}
