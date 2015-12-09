package ul.fcul.lasige.findvictim.ui;

import android.accounts.AccountManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.common.AccountPicker;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import ul.fcul.lasige.findvictim.R;
import ul.fcul.lasige.findvictim.data.TokenStore;
import ul.fcul.lasige.findvictim.gcm.RegistrationIntentService;
import ul.fcul.lasige.findvictim.sensors.SensorsService;

public class MainActivity extends AppCompatActivity implements SensorsService.Callback {
    private static final String TAG = MainActivity.class.getSimpleName();

    // gcm
    private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    private BroadcastReceiver mRegistrationBroadcastReceiver;

    // server registration
    private static final int REQUEST_CODE_EMAIL = 1;
    private String mGoogleAccount;

    // sensor service
    private ServiceConnection mSensorsConnection;
    private SensorsService mSensors;

    // ui
    private Button mToggleButton;
    private TextView mDescriptionView;
    private View.OnClickListener mOnTryAgainListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mToggleButton = (Button) findViewById(R.id.toggleButton);
        mDescriptionView = (TextView) findViewById(R.id.descriptionView);

        final CoordinatorLayout coordinatorLayout = (CoordinatorLayout) findViewById(R.id.coordinatorLayout);

        mToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mSensors != null) {
                    boolean isActivated;
                    if(isActivated = mSensors.isActivated()) {
                        // turn off
                        mSensors.deactivateSensors();
                    }
                    else {
                        mSensors.activateSensors();
                    }
                    toggleState(!isActivated);
                }
            }
        });

        mOnTryAgainListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                registerGCM();
            }
        };

        // google cloud messaging (gcm)
        mRegistrationBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean sentToken = TokenStore.isRegistered(getApplicationContext());
                if (sentToken) {
                    Snackbar.make(coordinatorLayout, "Congratulations! The registration is complete", Snackbar.LENGTH_LONG).show();
                } else {
                    Log.d(TAG, "token not sent");
                    Snackbar snack = Snackbar.make(coordinatorLayout, "Registration failed. Check your Internet connection",
                            Snackbar.LENGTH_INDEFINITE);
                    snack.setAction("Try again", mOnTryAgainListener);
                    snack.show();
                }
            }
        };

        if (checkPlayServices()) {
            // play services are installed

            boolean sentToken = TokenStore.isRegistered(getApplicationContext());
            if(!sentToken) {
                // if not registered, then ask for google account
                try {
                    Intent intent = AccountPicker.newChooseAccountIntent(null,
                            null,
                            new String[]{GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE},
                            false, null, null, null, null);
                    // we need to wait for the result
                    startActivityForResult(intent, REQUEST_CODE_EMAIL);
                } catch (ActivityNotFoundException e) {
                    mGoogleAccount = "noID";
                    registerGCM();
                }
            }
        }

        // start sensor service
        SensorsService.startSensorsService(this);
    }

    @Override
    protected void onStart() {
        super.onStart();

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
                mSensors.addCallback(MainActivity.this);

                // get sensor service state
                toggleState(mSensors.isActivated());
            }
        };
        bindService(new Intent(this, SensorsService.class), mSensorsConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(mRegistrationBroadcastReceiver,
                new IntentFilter(TokenStore.KEY_REGISTRATION_COMPLETE));
    }

    @Override
    protected void onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mRegistrationBroadcastReceiver);
        super.onPause();
    }

    private void toggleState(boolean isActive) {
        if(isActive) {
            mToggleButton.setText(R.string.stop);
            mDescriptionView.setText(R.string.stop_description);
        }
        else {
            mToggleButton.setText(R.string.start);
            mDescriptionView.setText(R.string.start_description);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        if(mSensors != null) {
            mSensors.removeCallback(this);
        }
        // unbind from sensor service
        unbindService(mSensorsConnection);
        mSensorsConnection = null;
    }

    /**
     * Check the device to make sure it has the Google Play Services APK. If
     * it doesn't, display a dialog that allows users to download the APK from
     * the Google Play Store or enable it in the device's system settings.
     */
    private boolean checkPlayServices() {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        int resultCode = apiAvailability.isGooglePlayServicesAvailable(this);
        if (resultCode != ConnectionResult.SUCCESS) {
            if (apiAvailability.isUserResolvableError(resultCode)) {
                apiAvailability.getErrorDialog(this, resultCode, PLAY_SERVICES_RESOLUTION_REQUEST)
                        .show();
            } else {
                Log.i(TAG, "This device is not supported.");
                finish();
            }
            return false;
        }
        return true;
    }

    /**
     * Result from Google Account picker activity
     * @param requestCode
     * @param resultCode
     * @param data
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_EMAIL && resultCode == RESULT_OK) {
            String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            mGoogleAccount = accountName;
        } else {
            mGoogleAccount= "noID";
        }
        Log.d(TAG, "Google account: " + mGoogleAccount);
        registerGCM();
    }

    private void registerGCM() {

        // get locale
        String locale = getResources().getConfiguration().locale.getCountry();

        // gets mac_address (user identification)
        WifiManager manager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = manager.getConnectionInfo();
        String mac= info.getMacAddress();

        // Start IntentService to register this application with GCM.
        RegistrationIntentService.startGCMRegistration(this, locale, mac, mGoogleAccount);
    }

    /*
     * MENUS
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivationStateChanged(final boolean activated) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                toggleState(activated);
            }
        });
    }
}
