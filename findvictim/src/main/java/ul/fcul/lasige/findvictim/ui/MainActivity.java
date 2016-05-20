package ul.fcul.lasige.findvictim.ui;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;

import com.example.unzi.findalert.ui.RegisterInFind;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.common.AccountPicker;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.util.Locale;

import ul.fcul.lasige.find.lib.data.Packet;
import ul.fcul.lasige.find.lib.data.PacketObserver;
import ul.fcul.lasige.findvictim.R;
import ul.fcul.lasige.findvictim.data.TokenStore;
import ul.fcul.lasige.findvictim.sensors.SensorsService;
import ul.fcul.lasige.findvictim.utils.DeviceUtils;

public class MainActivity extends AppCompatActivity implements SensorsService.Callback {
    private static final String TAG = MainActivity.class.getSimpleName();

    // sensor service
    private ServiceConnection mSensorsConnection;
    private SensorsService mSensors;

    // ui
    private Button mToggleButton;
    private TextView mDescriptionView;


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
                        mSensors.activateSensors(true);
                    }
                    toggleState(!isActivated);
                }
            }
        });


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

    }

    @Override
    protected void onPause() {
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
