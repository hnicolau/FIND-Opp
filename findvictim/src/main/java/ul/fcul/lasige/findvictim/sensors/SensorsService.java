package ul.fcul.lasige.findvictim.sensors;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Base64;
import android.util.Log;

import com.example.unzi.findalert.ui.RegisterInFind;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.lib.data.NeighborObserver;
import ul.fcul.lasige.find.lib.data.Packet;
import ul.fcul.lasige.find.lib.data.PacketObserver;
import ul.fcul.lasige.find.lib.service.FindConnector;
import ul.fcul.lasige.findvictim.R;
import ul.fcul.lasige.findvictim.data.Message;
import ul.fcul.lasige.findvictim.data.MessageGenerator;
import ul.fcul.lasige.findvictim.data.TokenStore;
import ul.fcul.lasige.findvictim.gcm.ReceiverGCM;
import ul.fcul.lasige.findvictim.network.ConnectivityChangeReceiver;
import ul.fcul.lasige.findvictim.utils.DeviceUtils;
import ul.fcul.lasige.findvictim.utils.NetworkUtils;
import ul.fcul.lasige.findvictim.utils.PositionUtils;
import ul.fcul.lasige.findvictim.webservices.RequestServer;
import ul.fcul.lasige.findvictim.webservices.WebLogging;

/**
 * Created by hugonicolau on 26/11/15.
 */
public class SensorsService extends Service implements PacketObserver.PacketCallback,
        NeighborObserver.NeighborCallback/*, InternetObserver.InternetCallback */ {
    private static final String TAG = SensorsService.class.getSimpleName();

    // start action
    public static final String ACTION_START = "ul.fcul.lasige.findvictim.action.START_SENSORS";

    // default state
    public static final SensorsState DEFAULT_STATE = SensorsState.IDLE;
    // state handlers
    private HandlerThread mHandlerThread; // thread with queue and looper
    private Handler mStateHandler; // processes state changes
    private volatile SensorsState mState = SensorsState.STOPPED;

    // callbacks for state changes
    private Set<Callback> mCallbacks = new HashSet<>();

    // wakelock to force CPU to keep running even when screen if off
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;

    // sensors
    private SensorManager mSensorManager;
    // scheduler to generate messages
    private ScheduledExecutorService mAsyncExecutorService;

    // communication (FIND) variables
    private FindConnector mConnector;

    // connectivity receiver
    private static ConnectivityChangeReceiver mConnectivityReceiver = null;

    private BroadcastReceiver mUploadBroadcastReceiver;
    private boolean mHasInternet = false;

    //boolean that allows the service to run even without alert.
    private boolean mManualStart = true;

    //gcm
    private ReceiverGCM mReceiverGCM;


    /*
     * EXTERNAL API
     */

    /**
     * Convenience method to start the supervisor service.
     *
     * @param context
     */
    public static void startSensorsService(Context context) {
        Log.d(TAG, "ON START SENRSOR SERVICE");

        final Intent startIntent = new Intent(context, SensorsService.class);
        startIntent.setAction(ACTION_START);
        context.startService(startIntent);
    }

    public static boolean bindSensorsService(Context context, ServiceConnection connection) {
        final Intent bindIntent = new Intent(context, SensorsService.class);
        return context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE);
    }

    public static interface Callback {
        public void onActivationStateChanged(boolean activated);
    }

    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    private void notifyCallbacks(boolean activated) {
        for (Callback callback : mCallbacks) {
            callback.onActivationStateChanged(activated);
        }
    }


    /*
     * SERVICE
     */
    @Override
    public IBinder onBind(Intent intent) {
        return new SensorsBinder();
    }

    @Override
    public void onCreate() {
        //register gcm and select server
        RegisterInFind findRegister = RegisterInFind.sharedInstance(this);
        mReceiverGCM = new ReceiverGCM(this);
        findRegister.register();


        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mStateHandler = new Handler(mHandlerThread.getLooper());

        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FindSensorsWakeLock");
        mWakeLock.setReferenceCounted(false);

        // get FIND platform connect
        mConnector = FindConnector.getInstance(this);
        // bind to platform
        bindToFind();

        // create sensor manager
        mSensorManager = new SensorManager();
        // add sensors
        mSensorManager.addSensor(SensorManager.SensorType.Battery, new BatterySensor(getApplicationContext()), false);
        mSensorManager.addSensor(SensorManager.SensorType.Location, new LocationSensor(getApplicationContext()), false);

        mUploadBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Finish uploading");
                if (intent != null && intent.getAction().equals(RequestServer.ACTION_PACKETS_SENT)) {
                    boolean success = intent.getBooleanExtra(RequestServer.EXTRA_PACKETS_SENT, false);
                    if (success) {
                        Log.d(TAG, "---------------- Packets sent! -----------------");
                        if (mConnectivityReceiver != null) {
                            try {
                                getApplicationContext().unregisterReceiver(mConnectivityReceiver);
                            } catch (IllegalArgumentException e) {
                                Log.d(TAG, "mConnectivity Receiver not register");
                            }

                        }
                    } else if (mHasInternet) {
                        // if fail keep trying while you have an internet connection, but release lock
                        Log.d(TAG, "Sync has Internet ");
                        sync();
                    }
                    Log.d(TAG, "Released Internet LOCK");
                    mConnector.releaseInternetLock();
                }
            }
        };
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(
                mUploadBroadcastReceiver, new IntentFilter(RequestServer.ACTION_PACKETS_SENT));

        SensorsState previousState = TokenStore.getSensorsState(getApplicationContext());
        // start sensing
        scheduleStateTransition(previousState);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // stop all sensors
        mSensorManager.removeAllSensors(true);

        // quit thread
        mHandlerThread.quit();

        if (mConnectivityReceiver != null)
            getApplicationContext().unregisterReceiver(mConnectivityReceiver);

        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(mUploadBroadcastReceiver);

        // unbind from platform
        unbindFromFind();

        // release wake lock
        releaseWakeLock();
    }

    /**
     * Custom binder which allows retrieving the current supervisor service instance.
     *
     * @author hugonicolau
     */
    public class SensorsBinder extends Binder {
        public SensorsService getSensors() {
            return SensorsService.this;
        }
    }

    private void acquireWakeLock() {
        if (!mWakeLock.isHeld()) {
            //Log.v(TAG, "WakeLock acquired");
            mWakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock.isHeld()) {
            //Log.v(TAG, "WakeLock released");
            mWakeLock.release();
        }
    }

    public SensorManager getSensorManager() {
        return mSensorManager;
    }

    public void activateSensors(boolean manualStart) {
        mManualStart = manualStart;
        if (!isActivated()) {
            WebLogging.logMessage(this, "started sensors", DeviceUtils.getWifiMacAddress(), "FindVictim");
            scheduleStateTransition(SensorsState.RUNNING);
        }
    }

    public void deactivateSensors() {
        Log.v(TAG, "Deactivate  Sensors");

        if (isActivated()) {
            // clear scheduled state transitions and instead go to IDLE next
            mStateHandler.removeCallbacksAndMessages(null);
            scheduleStateTransition(SensorsState.IDLE);
        }
    }

    public boolean isActivated() {
        return mState == SensorsState.RUNNING;
    }

    private synchronized void setState(SensorsState newState) {
        Log.v(TAG, "Transitioning from " + mState + " to " + newState);

        // leave previous state
        mState.onLeave(this);

        // update var
        mState = newState;
        TokenStore.saveSensorsState(this, newState);

        // enter new state
        newState.onEnter(this);
    }

    private synchronized void scheduleStateTransition(final SensorsState newState) {
        mStateHandler.post(new Runnable() {
            @Override
            public void run() {
                if (newState.equals(mState)) {
                    // the supervisor is already in the target state.
                    return;
                } else if (!newState.isPossibleOrigin(mState)) {
                    // the transition is not possible
                    throw new IllegalStateException("Can not transition from " + mState + " to " + newState);
                }

                // perform state transition (onLeave -> onEnter) and execute main body of new state
                setState(newState);
                newState.execute(SensorsService.this);
            }
        });
    }

    private void bindToFind() {
        // FIND
        Log.d(TAG, "Trying to bind with FIND platform ...");
        if (mConnector.bind(null/*this*/)) {
            Log.d(TAG, "Bind was successful");
            mConnector.registerProtocolsFromResources(R.xml.protocols, this, this);
        }
    }

    private void startFind() {
        mConnector.requestStart();
    }

    private void stopFind() {
        mConnector.requestStop();
    }

    private void unbindFromFind() {
        // FIND
        Log.d(TAG, "Unbinding with FIND platform");
        mConnector.unbind();
    }

    private void startSensors() {
        Log.d(TAG, "Starting sensors");
        mSensorManager.startAllSensors();
    }

    private void stopSensors() {
        Log.d(TAG, "Stopping sensors");
        mSensorManager.stopAllSensors();

    }

    private void startSendingMessages() {
        // start generating messages
        mAsyncExecutorService = Executors.newScheduledThreadPool(2);
        mAsyncExecutorService.scheduleAtFixedRate(
                new MessageGenerator.GenerateMessageTask(getApplicationContext(), mSensorManager, this),
                30, 60, TimeUnit.SECONDS);
    }

    private void stopSendingMessages() {
        // stop generating messages
        mAsyncExecutorService.shutdownNow();
        mAsyncExecutorService = null;
    }

    public void sendMessage(Message message) {

        Log.d(TAG, "Sending message ...");
        Log.d(TAG, "Battery level: " + message.Battery);
        Log.d(TAG, "Location Lat: " + message.LocationLatitude + " Lon: " + message.LocationLongitude + " Acc: " + message.LocationAccuracy + " Time: " + message.LocationTimestamp);

        // if it is the first time we have location
      /*  if (TokenStore.isFirstLocation(getApplicationContext()) && !mManualStart) {

            // if it is a valid location
            if (message.LocationLongitude != 0 && message.LocationLatitude != 0) {
                // get current alert
                Cursor cursor = Alert.Store.fetchAlerts(
                        DatabaseHelper.getInstance(getApplicationContext()).getReadableDatabase(),
                        Alert.STATUS.ONGOING);

                if (!cursor.moveToFirst()) {
                    // no current ongoing alerts, stop SensorsService
                    cursor.close();
                    scheduleStateTransition(SensorsState.IDLE);
                    return;
                }
                // check whether we are inside the alert area
                Alert alert = Alert.fromCursor(cursor);

                if (PositionUtils.isInLocation(message.LocationLatitude, message.LocationLongitude, alert.getLatStart(),
                        alert.getLonStart(), alert.getLatEnd(), alert.getLonEnd())) {
                    TokenStore.setIsFirstLocation(getApplicationContext(), false);
                } else {
                    // if not, then stop SensorsService
                    cursor.close();
                    scheduleStateTransition(SensorsState.IDLE);
                    return;
                }

                cursor.close();
            }
        }*/

        // if we reached this point it is because either: 1) we don't know our location or 2) we are inside the alert area

        // enqueue message to the communication platform
        if (mConnector != null) {
            // enqueue packet
            try {
                String protocol = mConnector.getProtocolToken("ul.fcul.lasige.findvictim");
                mConnector.enqueuePacket(protocol, message.getJSON().toString().getBytes("UTF-8"));

                // try to connect to current neighbors
                mConnector.requestDiscovery();
                Log.d(TAG, "Enqueued message");

               /* // try to send to server
                if (mHasInternet){
                    Log.d(TAG, "Sync has Internet on enqueu");
                    //sync();
                }*/

            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        } else {
            Log.d(TAG, "No longer connected to enqueue message");
        }
    }

    public static enum SensorsState {
        /**
         * The supervisor service is stopped (not instantiated).
         */
        STOPPED {
            @Override
            public boolean isPossibleOrigin(SensorsState state) {
                // STOPPED can not be reached from anywhere else
                return false;
            }
        },
        /**
         * The service is up and running, but doing nothing yet. From here, an activation
         * request is needed to transition further to the {@link SensorsState#RUNNING} state.
         * <p/>
         * This state is also transitioned to in case of a deactivation request.
         */
        IDLE {
            @Override
            public void onEnter(SensorsService ss) {
            }

            @Override
            public void onLeave(SensorsService ss) {
            }
        },
        /**
         * The service is enabled and starts collecting sensor data. From here, a deactivation
         * request is needed to transition further to the {@link SensorsState#IDLE} state.
         * <p/>
         * When in this state, the supervisor holds a partial wake lock.
         *
         * @see PowerManager.WakeLock
         */
        RUNNING {
            @Override
            public void onEnter(SensorsService ss) {
                ss.notifyCallbacks(true);
                ss.acquireWakeLock();
                ss.startFind();
                ss.startSensors();
                ss.startSendingMessages();
            }

            @Override
            public void onLeave(SensorsService ss) {
                ss.stopSendingMessages();
                ss.stopSensors();
                ss.stopFind();
                ss.releaseWakeLock();
                ss.syncWithServer();
                ss.notifyCallbacks(false);

            }
        };

        /**
         * Executes when entering this state. By default, this method does nothing.
         *
         * @param sv
         */
        public void onEnter(SensorsService sv) {
        }

        /**
         * Executes the main function of this state. By default, this method does nothing.
         *
         * @param sv
         */
        public void execute(SensorsService sv) {
        }

        /**
         * Executes when leaving this state. By default, this method does nothing.
         *
         * @param sv
         */
        public void onLeave(SensorsService sv) {
        }

        /**
         * Checks if this state can be transitioned to from the specified state. By default, this
         * method returns {@code true}, except the specified state is the same as this one.
         *
         * @param state the state from which the transition would occur
         * @return true if the transition is possible, false otherwise
         */
        public boolean isPossibleOrigin(SensorsState state) {
            return !state.equals(this);
        }
    }

    /*
     * FIND CALLBACKS
     */
    @Override
    public void onNeighborConnected(Neighbor currentNeighbor) {

    }

    @Override
    public void onNeighborDisconnected(Neighbor recentNeighbor) {

    }

    @Override
    public void onNeighborsChanged(Set<Neighbor> currentNeighbors) {

    }

    @Override
    public void onPacketReceived(Packet packet, Uri ui) {

        String downloadProtocol = mConnector.getProtocolToken("ul.fcul.lasige.downloading");
        String victimProtocol = mConnector.getProtocolToken("ul.fcul.lasige.findvictim");

        if (downloadProtocol.equals(ui.getQueryParameters("protocol_token").get(0))) {
            //do something with packet
            String data = new String(packet.getData());
            Log.d(TAG, "Packet downloaded:::" + data);
        } else {
            if (victimProtocol.equals(ui.getQueryParameters("protocol_token").get(0))) {
                //do something with packet
                String data = new String(packet.getData());
                Log.d(TAG, "Packet victim:::" + data);
            }
        }
    }

   /* @Override
    public void onInternetConnection(boolean connected) {
        Log.d(TAG, "----- OnInternetConnection: " + connected);


        mHasInternet = connected;
       if (connected && isActivated()) {
           // lock connection
            mConnector.acquireInternetLock();
           sync();
        }
    }*/

    /*
     * INTERNET CONNECTION
     */
    private void syncWithServer() {
        Log.d(TAG, "Trying to sync with server");
        Handler handler = new Handler();
        handler.post(new Runnable() {
            @Override
            public void run() {

                // do we have an Internet connection?
                if (NetworkUtils.isOnline(getApplicationContext())) {
                    // yes we do! sync ...
                    Log.d(TAG, "Sync with server Internet ");

                    sync();
                } else {
                    // no, try again when connectivity changes
                    // register receiver
                    mConnectivityReceiver = new ConnectivityChangeReceiver(SensorsService.this);
                    getApplicationContext().registerReceiver(mConnectivityReceiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
                }
            }
        });
    }

    public synchronized void sync() {
        Log.d(TAG, "Going to sync with server");

        // call web service and unregister receiver in case of success
        new Thread(new Runnable() {
            @Override
            public void run() {
                // get all outgoing messages
                ArrayList<Packet> packets = new ArrayList<Packet>(mConnector.getOutgoingPackets());
                String[] messages = new String[packets.size()];
                int index = 0;
                for (Packet packet : packets) {
                    Log.d(TAG, "Synching; " + new String(packet.getData()));

                    String base64Encoded = Base64.encodeToString(packet.getData(), Base64.DEFAULT);
                    messages[index] = base64Encoded;
                    index++;
                }
                if (messages.length > 0) {
                    RequestServer.sendPackets(getApplicationContext(), messages);
                } else {
                    Log.d(TAG, "No messages");
                }


            }
        }).start();

    }

}
