package ul.fcul.lasige.find.beaconing;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import ul.fcul.lasige.find.apps.ProtocolRegistry;
import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.data.FullContract;
import ul.fcul.lasige.find.data.Identity;
import ul.fcul.lasige.find.network.NetworkManager;
import ul.fcul.lasige.find.network.NetworkStateChangeReceiver;
import ul.fcul.lasige.find.packetcomm.PacketCommManager;
import ul.fcul.lasige.find.protocolbuffer.FindProtos;
import ul.fcul.lasige.find.service.SynchronizedPackets;
import ul.fcul.lasige.find.utils.InterruptibleFailsafeRunnable;

/**
 * BeaconingManager is a Singleton class, thus it should be accessed through {@link BeaconingManager#getInstance(Context)}.
 *
 * <p>This class is responsible for managing the state machine of the beaconing stage, which includes
 * starting/stoping beacon listeners, actively search for neighbors, parse and create beacons, and so forth.</p>
 *
 * Created by hugonicolau on 12/11/15.
 */
public class BeaconingManager implements NetworkStateChangeReceiver.NetworkChangeListener {
    /**
     * Broadcast action: Sent after a beaconing period has completed successfully (i.e., has not
     * been aborted). Carries the beaconing ID (which was used to activate the beaconing in the
     * first place) as an extra.
     *
     * @see #EXTRA_BEACONING_ID
     *
     */
    public static final String ACTION_BEACONING_FINISHED = "ul.fcul.lasige.find.action.BEACONING_FINISHED";

    /**
     * Lookup key for the beaconing ID which just finished.
     *
     * @see Intent#getIntExtra(String, int)
     */
    public static final String EXTRA_BEACONING_ID = "beaconing_id";

    // port used to listen for beacons
    protected static final int RECEIVER_PORT_UNICAST = 3108;
    /*protected static final int RECEIVER_PORT_MULTICAST = 5353;
    protected static final InetAddress[] MULTICAST_GROUPS = {
            InetAddresses.forString("224.0.0.251"), InetAddresses.forString("ff02::fb")
    };*/

    protected static final int RECEIVER_SOCKET_TIMEOUT = 25 * 1000; // 25 seconds
    protected static final int RECEIVER_BUFFER_SIZE = 4 * 1024; // 4 KiB

    protected static final String SDP_NAME = "FindBeaconingManager";
    protected static final UUID FIND_UUID = UUID.fromString("35b0a0a8-c92a-4c63-b7d8-d0a55ca18159");

    // TODO we're only using multicast at the moment
    protected enum SocketType { UNICAST, MULTICAST, RFCOMM }

    private static final String TAG = BeaconingManager.class.getSimpleName();

    // singleton instance
    private static BeaconingManager sInstance;

    // general variables
    protected final Context mContext;
    protected final PowerManager mPowerManager;
    protected final NetworkManager mNetManager;
    protected final DbController mDbController;
    protected final ProtocolRegistry mProtocolRegistry;
    protected final Identity mMasterIdentity;

    // packet manager, handles all message (not beacons) communication with neighbors
    protected final PacketCommManager mPacketCommManager;

    // beacon processing
    protected ScheduledExecutorService mThreadPool;
    protected BeaconParser mBeaconParser; // thread that parses received beacons
    protected InterruptibleFailsafeRunnable mBeaconingInterval; // this is the main thread that is running when looking for neighbors

    // beacon sending/receiving
    protected UdpReceiver mUnicastReceiver; // beacon receiver
    /*protected UdpReceiver mMulticastReceiver;*/
    protected WeakReference<UdpSender> mOneTimeWifiSender; // beacon sender
    protected ScheduledFuture<?> mRegularWifiSender; // beacon sender
    /*protected RfcommReceiver mBluetoothReceiver;
    protected WeakReference<RfcommSender> mBtSender;
    protected final Map<String, BluetoothSocket> mBluetoothSockets = new HashMap<>();*/
    protected final BeaconBuilder mBeaconBuilder;

    private int mWifiConnectionLockCount; // used to keep network state
    /*private int mBtConnectionLockCount;*/

    // beaconing state
    protected volatile BeaconingState mState = BeaconingState.STOPPED;
    protected volatile Policy mPolicy;
    private BroadcastReceiver mPolicyChangedReceiver;
    private int mCurrentBeaconingRoundId; // current beaconing process id

    // AP mode
    private int mCurrentApLikelihood;
    protected boolean mIsDesignatedAp;

    // callbacks
    // internet callback
    private Set<InternetCallback> mInternetCallbacks = new HashSet<>();
    protected Map<String, Integer> mInternetLockCount = new HashMap<>(); // used to keep current internet connection
    protected boolean mHasInternet = false;
    private Handler mLockResetTimeout = new Handler();
    private final int mTimeoutInternetLock=60*5*1000;
    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            resetInternetLock();
        }
    };

    /**
     * Constructor.
     * @param context Application context
     */
    private BeaconingManager(Context context) {
        mContext = context;

        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mNetManager = NetworkManager.getInstance(mContext);
        mDbController = new DbController(mContext);
        mProtocolRegistry = ProtocolRegistry.getInstance(mContext);
        mMasterIdentity = mDbController.getMasterIdentity();
        mBeaconBuilder = new BeaconBuilder(this);
        mPacketCommManager = new PacketCommManager(mContext, this);
        mHasInternet = mNetManager.hasInternetAccess();
    }

    /**
     * Retrieves singleton instance of {@link BeaconingManager}.
     * @param context Application context.
     * @return A {@link BeaconingManager} object.
     */
    public static synchronized BeaconingManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new BeaconingManager(context);
        }
        return sInstance;
    }

    /*
     * INTERNET CALLBACK
     */
    public interface InternetCallback { void onInternetConnection(boolean connected); }

    /**
     * Adds a callback that will be trigger on Internet connectivity changes.
     * @param internetCallback Callback.
     */
    public void addInternetCallback(InternetCallback internetCallback) {
        Log.d(TAG, "Internet callback added");
        mInternetCallbacks.add(internetCallback);
    }

    /**
     * Remove previsously added callback.
     * @param internetCallback Callback.
     */
    public void removeInternetCallback(InternetCallback internetCallback) { mInternetCallbacks.remove(internetCallback); }

    /**
     * Starts the default synchronization to endpoints and notifies all Internet callbacks.
     * @param connected Connectivity status.
     */
    private void notifyInternetCallbacks(boolean connected) {
        if(connected) {
            SynchronizedPackets.syncPackets(mContext);
        }

        for (InternetCallback callback : mInternetCallbacks) {
            callback.onInternetConnection(connected);
        }
    }

    /**
     * Notifies all Internet callbacks with current Internet connectivity status.
     */
    public void notifyInternetCallbacks() {
        notifyInternetCallbacks(hasInternetAccess());
    }

    /**
     * Returns Internet connectivity status.
     * @return true if there the Internet is currently accessible, false otherwise.
     */
    public boolean hasInternetAccess() {
        mHasInternet = mNetManager.hasInternetAccess();
        return mHasInternet;
    }

    /*
     * BEACONING STATE
     */

    /**
     * Transitions to the new {@link BeaconingState}. If the new and current state are the same, then
     * there is no transition. An {@link IllegalStateException} is thrown if the new state can't be
     * reached from the current state.
     * @param newState New {@link BeaconingState}.
     * @see BeaconingState
     */
    private synchronized void doStateTransition(final BeaconingState newState) {
        if (newState.equals(mState)) {
            // the beaconing manager is already in the target state.
            return;
        } else if (!newState.isPossibleOrigin(mState)) {
            // the transition is not possible
            throw new IllegalStateException("Can not transition from " + mState + " to " + newState);
        }

        // perform state transition and execute main body of new state
        Log.v(TAG, "Transitioning from " + mState + " to " + newState);

        // leave current state
        mState.onLeave(this);

        // update current state
        mState = newState;

        // enter new state
        newState.onEnter(this);

        // execute new state
        newState.execute(this);

        // notify neighbor content resolvers
        notifyNeighborUris(mContext);
    }

    /**
     * Creates thread pool.
     * @see Thread
     * @see ScheduledExecutorService
     */
    private void setupThreadPool() {
        if (mThreadPool == null) {
            mThreadPool = Executors.newScheduledThreadPool(10);
        }
    }

    /**
     * Destroys thread pool and stops beacon senders/receivers
     * @see Thread
     * @see ScheduledExecutorService
     */
    private void teardownThreadPool() {
        stopBeaconSenders();
        stopBeaconReceivers();
        if (mThreadPool != null) {
            mThreadPool.shutdownNow();
            mThreadPool = null;
        }
        Log.v(TAG, "Thread pool shut down");
    }

    /**
     * Creates policy broadcast receiver in case it isn't already created and initializes current policy.
     */
    private void setupPolicyReceiver() {
        if (mPolicyChangedReceiver == null) {
            mPolicyChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mPolicy = (Policy) intent.getSerializableExtra(Policy.EXTRA_NEW_POLICY);
                    sCurrentBeaconingInterval = mPolicy.getBeaconingInterval();
                }
            };
            Policy.registerPolicyChangedReceiver(mContext, mPolicyChangedReceiver);
            mPolicy = Policy.getCurrentPolicy(mContext);
        }
    }

    /**
     * Destroys policy broadcast receiver
     */
    private void teardownPolicyReceiver() {
        if (mPolicyChangedReceiver != null) {
            Policy.unregisterPolicyChangedReceiver(mContext, mPolicyChangedReceiver);
            mPolicyChangedReceiver = null;
            mPolicy = null;
        }
    }


    /*
     * BEACON RECEIVERS
     */
    /**
     * Activate beacon receivers. This operation is not synchronous; only after connecting to a neighbor
     * or network, beacons receiver will start.
     *
     * <p>This method creates a network save point and acquires a lock. It also starts the {@link BeaconParser} thread
     * (to handle received beacons) and the {@link PacketCommManager} (to send packets to neighbors).</p>
     */
    private void activateBeaconReceivers() {
        // create save point
        mNetManager.createSavepoint();
        // acquire lock
        mNetManager.acquireLocks();

        // start beacon parser thread to handle received beacons
        mBeaconParser = new BeaconParser(this);
        mThreadPool.execute(mBeaconParser);

        // upon a successful connection, the change receiver will start the wifi beacon receivers
        // (it's a sticky broadcast). However, that does not apply to bluetooth, so we need to take
        // care of them manually.
        mNetManager.registerForConnectivityChanges(this);
        /*TODO if (mNetManager.isBluetoothEnabled()) {
            startBluetoothReceiver();
        }*/

        // start packet manager to handle packet communications with neighbors
        mPacketCommManager.start();
    }

    /**
     * Deactivate beacons receivers. This is a synchronous operation.
     *
     * <p>In addition to stop beacon receivers, it stops the {@link PacketCommManager}, the {@link BeaconParser} thread,
     * release connection lock and rolls back to original network state (if possible).</p>
     */
    private void deactivateBeaconReceivers() {
        // stop packet communication
        mPacketCommManager.stop();
        // we are no longer interested in knowing whether there is a new connection
        mNetManager.unregisterForConnectivityChanges(this);
        // stop beacon receivers
        stopBeaconReceivers();
        // stop beacon parser thread
        mBeaconParser.interrupt();
        // release locks
        mNetManager.releaseLocks();
        // rollback to previous network state if possible
        mNetManager.rollback();
    }

    /**
     * Start WiFi beacon receiver.
     */
    protected void startWifiReceiver() {
        try {
            // start unicast receiver
            if (mUnicastReceiver == null) {
                mUnicastReceiver = new UdpReceiver.UdpUnicastReceiver(this);
                mThreadPool.execute(mUnicastReceiver);
            }

            // start multicast receiver
            /*TODO if (mNetManager.getWifiState().equals(NetworkManager.WifiState.STA_ON_PUBLIC_AP)) {
                // Multicast is only supported on public networks
                 if (mMulticastReceiver == null) {
                    mMulticastReceiver = new UdpMulticastReceiver(this);
                    mThreadPool.execute(mMulticastReceiver);
                }
            }*/
        } catch (IOException e) {
            Log.e(TAG, "Error while creating WiFi receivers:", e);
        }
    }

    /**
     * Stop WiFi beacon receiver.
     */
    protected void stopWifiReceiver() {
        // stop unicast receiver
        if (mUnicastReceiver != null) {
            mUnicastReceiver.interrupt();
            mUnicastReceiver = null;
        }

        // stop multicast receiver
        /*TODO if (mMulticastReceiver != null) {
            mMulticastReceiver.interrupt();
            mMulticastReceiver = null;
        }*/
    }

    /**
     * Start Bluetooth receiver.
     */
    protected void startBluetoothReceiver() {
        /*try {
            if (mBluetoothReceiver == null) {
                mBluetoothReceiver = new RfcommReceiver(this);
                mThreadPool.execute(mBluetoothReceiver);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error while creating Bluetooth receiver, aborting.", e);
        }*/
    }

    /**
     * Stop Bluetooth receiver.
     */
    protected void stopBluetoothReceiver() {
        /*TODO if (mBluetoothReceiver != null) {
            mBluetoothReceiver.interrupt();
            mBluetoothReceiver = null;
        }
        interruptBluetoothConnections();*/
    }

    /**
     * Interrups all Bluetooth connections by trying to close open sockets.
     */
    private void interruptBluetoothConnections() {
        /*TODO for (BluetoothSocket btSocket : mBluetoothSockets.values()) {
            try {
                btSocket.close();
            } catch (IOException e) {
                Log.e(TAG, String.format("Error while closing bluetooth socket to %s:",
                        btSocket.getRemoteDevice().getAddress()), e);
            }
        }
        mBluetoothSockets.clear();*/
    }

    /**
     * Stop all beacon receivers.
     */
    protected void stopBeaconReceivers() {
        stopWifiReceiver();
        //TODO stopBluetoothReceiver();
    }

    // BEACON SENDERS

    /**
     * Attemps to send a single beacon if there is a connection.
     */
    public void sendSingleBeacon() {
        if (mNetManager.isWifiConnected()) {
            startWifiSender(false);
        }
        /*TODO if (mNetManager.isBluetoothEnabled()) {
            startBluetoothSender(0);
        }*/
    }

    /**
     * Starts sending beacon(s). At least it sends one beacon to neighbors.
     * @param repeating If true, it sends burst of 3 beacons in intervals of 3 seconds.
     */
    protected void startWifiSender(boolean repeating) {
        if (mRegularWifiSender != null && !mRegularWifiSender.isDone()) {
            // The senders are already running
            Log.v(TAG, "Wifi senders already running");
            return;
        }

        mCurrentApLikelihood = 0;
        if (mNetManager.getWifiState().equals(NetworkManager.WifiState.FIND_AP)
                && mPolicy.allows(Policy.Feature.WIFI_AP)) {
            // signal willingness to take over AP mode
            mCurrentApLikelihood = (int) (Math.random() * 256);
        }
        mIsDesignatedAp = (mCurrentApLikelihood > 0);

        // start udp sender for a single beacon
        UdpSender oneTimeWifiSender = new UdpSender(this, true, 1, mCurrentApLikelihood);
        mOneTimeWifiSender = new WeakReference<>(oneTimeWifiSender);
        mThreadPool.execute(oneTimeWifiSender);

        if (repeating) {
            // send bursts of 3 beacons every 3 seconds
            UdpSender repeatingWifiSender = new UdpSender(this, false, 3, mCurrentApLikelihood);
            mRegularWifiSender =
                    mThreadPool.scheduleAtFixedRate(repeatingWifiSender, 2, 3, TimeUnit.SECONDS);
        }
    }

    /**
     * Stop all WiFi beacon senders.
     */
    protected void stopWifiSender() {
        // stop single beacon sender
        if (mOneTimeWifiSender != null) {
            UdpSender oneTimeSender = mOneTimeWifiSender.get();
            if (oneTimeSender != null) {
                oneTimeSender.interrupt();
                mOneTimeWifiSender = null;
            }
        }

        // stop repeating beacon sender
        if (mRegularWifiSender != null) {
            mRegularWifiSender.cancel(true);
            mRegularWifiSender = null;
        }
    }

    /*private void startBluetoothSender(int delay) {
        if (mBtSender == null || mBtSender.get() == null) {
            RfcommSender btSender = new RfcommSender(this);
            mBtSender = new WeakReference<RfcommSender>(btSender);
            mThreadPool.schedule(btSender, delay, TimeUnit.SECONDS);
        }
    }

    private void stopBluetoothSender() {
        if (mBtSender != null) {
            RfcommSender btSender = mBtSender.get();
            if (btSender != null) {
                btSender.interrupt();
                mBtSender = null;
            }
        }
    }
*/

    /**
     * Stop all beacon senders.
     */
    protected void stopBeaconSenders() {
        stopWifiSender();
        //TODO stopBluetoothSender();
    }

    /*
     * BEACONING INTERVAL
     */
    /**
     * Start {@link BeaconingIntervalHandler} thread. This is where the platform attempts to connect
     * to nearby neighbors.
     */
    private void startBeaconingInterval() {
        if (mBeaconingInterval == null) {
            // delete all previous processed beacons
            mBeaconParser.clearProcessedBeacons();
            // start thread
            mBeaconingInterval = new BeaconingIntervalHandler(this, mCurrentBeaconingRoundId);
            new Thread(mBeaconingInterval).start();
        }
    }

    /**
     * Stop {@link BeaconingIntervalHandler} thread.
     */
    private void stopBeaconingInterval() {
        // stop thread
        if (mBeaconingInterval != null) {
            mBeaconingInterval.interrupt();
            mBeaconingInterval = null;

            // because we are no longer listening for connectivity changes in the BeaconingIntervalHandler
            // we need to listen for them here.
            mNetManager.registerForConnectivityChanges(this);
        }
    }

    /*
     * CALLBACKS FROM RECEIVERS AND PARSERS
     */

    /**
     * Callback being called by {@link UdpReceiver} when a new beacon is received from a neighbor.
     * @param possibleBeacon Received beacon.
     */
    protected void onBeaconReceived(BeaconParser.PossibleBeacon possibleBeacon) {
        mBeaconParser.addProcessableBeacon(possibleBeacon);
    }

    /**
     * Callback being called by {@link BeaconParser} when it finishes parsing a beacon.
     *
     * <p>This method replies to the neighbor with the platform's protocols and updates the likelihood
     * of taking over as an AP based on neighbor's likelihood.</p>
     * @param beacon Received beacon.
     * @param rawData Data from beacon.
     * @param timeCreated Timestamp the beacon was originally created
     */
    protected void onBeaconParsed(FindProtos.Beacon beacon, BeaconParser.PossibleBeacon rawData, long timeCreated) {
        // if we are currently listening for beacons and this is not a reply beacon
        if (mState == BeaconingState.PASSIVE && beacon.getBeaconType() == FindProtos.Beacon.BeaconType.ORIGINAL) {
            // get neighbor
            final byte[] origin = rawData.getOrigin();
            if (origin.length != 6) {
                // reply to IPv4/IPv6 beacons (bluetooth beacons are always answered directly)
                try {
                    final InetAddress replyTo = InetAddress.getByAddress(origin);
                    // send likelihood of we becoming an AP along with our protocols and so forth.
                    UdpSender replySender = new UdpSender(this, replyTo, beacon, mCurrentApLikelihood);
                    mThreadPool.execute(replySender);
                } catch (UnknownHostException e) {
                    // should never happen
                }
            }
        }

        // if we are an AP, see how likely it is for the neighbor to take over
        if (mNetManager.getWifiState().equals(NetworkManager.WifiState.STA_ON_FIND_AP)) {
            // get neighbor's AP likelihood
            final int remoteApLikelihood = beacon.getSender().getApLikelihood() & 0xFF;
            // update our likelihood if we are more likely to take over
            // this will be used by the BeaconingIntervalHandler
            mIsDesignatedAp = mIsDesignatedAp
                    && (mCurrentApLikelihood > 0)
                    && (mCurrentApLikelihood >= remoteApLikelihood);
        }

        notifyNeighborUris(mContext);
    }

  /*  protected void onBtDeviceDisconnected(BluetoothDevice btDevice) {
        mBluetoothSockets.remove(btDevice.getAddress());
    }
*/

    /**
     * Callback being called by {@link BeaconingIntervalHandler} when finishes all beaconing rounds.
     *
     * <p>This method transition to {@link BeaconingState#PASSIVE PASSIVE} state and notifies the
     * platform (e.g. {@link ul.fcul.lasige.find.service.SupervisorService Supervisor}
     * that the beaconing round finished.</p>
     * @param beaconingId Beaconing round id.
     */
    public void onBeaconingIntervalFinished(int beaconingId) {
        doStateTransition(BeaconingState.PASSIVE);

        final Intent finishedIntent = new Intent(ACTION_BEACONING_FINISHED);
        finishedIntent.putExtra(EXTRA_BEACONING_ID, beaconingId);
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(finishedIntent);
    }

    /*protected boolean connectToBtDevice(BluetoothDevice btDevice) {
        if (mBluetoothSockets.containsKey(btDevice.getAddress())) {
            // Target device is already connected
            return true;
        }

        try {
            BluetoothSocket btSocket =
                    btDevice.createInsecureRfcommSocketToServiceRecord(OPP_NET_UUID);
            RfcommConnection btConnection = new RfcommConnection(this, btSocket, true);
            mBluetoothSockets.put(btDevice.getAddress(), btSocket);
            mThreadPool.execute(btConnection);
        } catch (IOException e) {
            Log.e(TAG, "Error while connecting to bluetooth device " + btDevice.getName());
            return false;
        }
        return true;
    }
*/

    /*
     * INTERNET STATE LOCK - used by client applications
     *  - the platform when downloading or uploading to endpoints
     *
     */
    /**
     * Acquire Internet lock to force platform to stay in current network.
     * @param appName Client application requesting the lock.
     */
    public synchronized void acquireInternetLock(String appName) {
        //if its the first internet lock we set a max timer to clear the lock
        if(mInternetLockCount.size()==0){
            mLockResetTimeout.postDelayed(mRunnable, mTimeoutInternetLock);
        }
        mInternetLockCount.put(appName, mInternetLockCount.size());
    }

    /**
     * Release Internet lock. Platform can now search other networks/neighbors.
     * @param appName Client application requesting the release.
     */
    public synchronized void releaseInternetLock(String appName) {
        mInternetLockCount.remove(appName);
        //check if all locks have been release an if so stop the timeout for the internet lock
        if(mInternetLockCount.isEmpty()){
            mLockResetTimeout.removeCallbacks(mRunnable);
        }
    }

    /**
     * Resets Internet locks, i.e. releases all locks.
     */
    public synchronized void resetInternetLock() {
        mInternetLockCount = new HashMap<>();
    }

    /**
     * Retrieves whether there is a current Internet lock.
     * @return true if there is (at least) a lock, false otherwise.
     */
    public boolean isInternetLocked() {
        return !mInternetLockCount.isEmpty();
    }


    /*
     * CONNECTION LOCKS - used by the platform
     */
    /**
     * Releases all WiFi connection locks and rollback to initial WiFi state.
     */
    public synchronized void resetWifiConnectionLock() {
        mWifiConnectionLockCount = 0;
        rollbackNetworkStateIfSuitable();
    }

    /**
     * Locks/releases WiFi connection. The method attempts to rollback to initial WiFi state.
     * @param locked true to lock, false to release.
     * @see BeaconingManager#rollbackNetworkStateIfSuitable()
     */
    public synchronized void setWifiConnectionLocked(boolean locked) {
        final int modifier = (locked ? 1 : -1);
        mWifiConnectionLockCount = Math.max(0, mWifiConnectionLockCount + modifier);
        rollbackNetworkStateIfSuitable();
    }

    /**
     * Returns whether there are active WiFi locks.
     * @return true if there are active locks, false otherwise.
     */
    public boolean isWifiConnectionLocked() {
        return mWifiConnectionLockCount > 0;
    }

    /*TODO public synchronized void resetBtConnectionLock() {
        mBtConnectionLockCount = 0;
        rollbackNetworkStateIfSuitable();
    }

    public synchronized void setBtConnectionLocked(boolean locked) {
        final int modifier = (locked ? 1 : -1);
        mBtConnectionLockCount = Math.max(0, mBtConnectionLockCount + modifier);
        rollbackNetworkStateIfSuitable();
    }

    public boolean isBtConnectionLocked() {
        return mBtConnectionLockCount > 0;
    }*/

    /**
     * Rollback network state if there are no current WiFi locks. It only rollback when {@link BeaconingManager}
     * is either on {@link BeaconingState#PASSIVE PASSIVE} or {@link BeaconingState#STOPPED STOPPED} state.
     * <p>Rollback consists in recovering the previously saved network state.</p>
     * @see NetworkManager#createSavepoint()
     * @see NetworkManager#rollback()
     */
    private void rollbackNetworkStateIfSuitable() {
        if (mWifiConnectionLockCount /*TODO + mBtConnectionLockCount*/ == 0) {
            // Locks are now completely released
            final int savepointCount = mNetManager.getSavepointCount();
            if ((mState == BeaconingState.PASSIVE && savepointCount > 1)
                    || (mState == BeaconingState.STOPPED && savepointCount > 0)) {
                mNetManager.rollback();
            }
        }
    }

    /*
     * NETWORK CHANGE LISTENERS - called by NetworkManager
     */
    /**
     * Callback for WiFi adapter changes. Empty by default.
     * @param enabled Indicates if adapter is enabled.
     */
    @Override
    public void onWifiAdapterChanged(boolean enabled) { }

    /**
     * Callback for WiFi network chages.
     *
     * <p>Notifies Internet callbacks.</p>
     *
     * <p>If {@link BeaconingManager} is on {@link BeaconingState#PASSIVE PASSIVE} state, then it
     * starts or stops WiFi receivers/senders based on connectivity. If connected, starts WiFi receivers
     * and senders. If disconnected, stops WiFi receivers and senders.</p>
     * @param connected Indicates whether there is a connection.
     * @param isFailover Indicates if it is a failover callback.
     */
    @Override
    public void onWifiNetworkChanged(boolean connected, boolean isFailover) {
        // check Internet connection and notify observers
        notifyInternetCallbacks();

        // if we are in active state, do not mess with senders/receivers
        if (mState == BeaconingState.PASSIVE) {
            if (connected) {
                // start
                startWifiReceiver();
                startWifiSender(false);
            } else {
                // stop
                stopWifiSender();
                stopWifiReceiver();
            }
        }
    }

    /**
     * Callback for Bluetooth adapter changes. Empty by default.
     * @param enabled Indicates whether the adapter is enabled.
     */
    @Override
    public void onBluetoothAdapterChanged(boolean enabled) {
        /*TODO if (mState == BeaconingState.PASSIVE) {
            if (enabled) {
                startBluetoothReceiver();
                startBluetoothSender(15);
            } else {
                stopBluetoothSender();
                stopBluetoothReceiver();
            }
        }*/
    }

    /**
     * Callback for Access Point (AP) changes.
     *
     * <p>Notifies Internet callbacks.</p>
     * @param activated Indicates whether the AP is activated.
     */
    @Override
    public void onAccessPointModeChanged(boolean activated) {
        // check Internet connection and notify observers
        notifyInternetCallbacks();
    }

    /*
     * EXTERNAL API
     */
    /**
     * Returns whether the {@link BeaconingManager} is in an active state; that is, different from
     * {@link BeaconingState#STOPPED STOPPED}.
     * @return whether current {@link BeaconingState} is different from {@link BeaconingState#STOPPED STOPPED} state.
     */
    public boolean isActivated() {
        return (mState != BeaconingState.STOPPED);
    }

    /**
     * Set current {@link BeaconingState} to {@link BeaconingState#STOPPED STOPPED}.
     */
    public void setStopped() {
        doStateTransition(BeaconingState.STOPPED);
    }

    /**
     * Set current {@link BeaconingState} to {@link BeaconingState#PASSIVE PASSIVE}.
     */
    public void setPassive() {
        doStateTransition(BeaconingState.PASSIVE);
    }

    /**
     * Set current {@link BeaconingState} to {@link BeaconingState#ACTIVE ACTIVE}.
     * @param beaconingRoundId Current beaconing round id.
     */
    public void setActive(int beaconingRoundId) {
        mCurrentBeaconingRoundId = beaconingRoundId;
        doStateTransition(BeaconingState.ACTIVE);
    }

    /**
     * {@link BeaconingManager} state representation: {@link BeaconingState#STOPPED}, {@link BeaconingState#PASSIVE},
     * {@link BeaconingState#ACTIVE}
     */
    public enum BeaconingState {
        /**
         * The beaconing manager is stopped (not instantiated).
         */
        STOPPED {
            @Override
            public void onEnter(BeaconingManager bm) {
                bm.deactivateBeaconReceivers();
                bm.teardownPolicyReceiver();
                bm.teardownThreadPool();
            }

            @Override
            public void onLeave(BeaconingManager bm) {
                bm.setupThreadPool();
                bm.setupPolicyReceiver();
                bm.activateBeaconReceivers();
            }

            @Override
            public boolean isPossibleOrigin(BeaconingState state) {
                return state.equals(PASSIVE);
            }
        },
        /**
         * The beaconing manager is started, but does not regularly send beacons on its own. It only
         * responds to other neighbors beacons, or sends a "beacon burst" if a network change has
         * been detected.
         */
        PASSIVE {
        },
        /**
         * The beaconing manager is actively scanning for neighbors. If the current {@link Policy}
         * allows it, this state also switches between different network modes.
         */
        ACTIVE {
            @Override
            public void onEnter(BeaconingManager bm) {
                bm.startBeaconingInterval();
            }

            @Override
            public void onLeave(BeaconingManager bm) {
                bm.stopBeaconingInterval();
            }

            @Override
            public boolean isPossibleOrigin(BeaconingState state) {
                return state.equals(PASSIVE);
            }
        };

        /**
         * Executes when entering this state. By default, this method does nothing.
         *
         * @param bm BeaconingManager object
         */
        public void onEnter(BeaconingManager bm) { }

        /**
         * Executes the main function of this state. By default, this method does nothing.
         *
         * @param bm BeaconingManager object
         */
        public void execute(BeaconingManager bm) { }

        /**
         * Executes when leaving this state. By default, this method does nothing.
         *
         * @param bm BeaconingManager object
         */
        public void onLeave(BeaconingManager bm) { }

        /**
         * Checks if this state can be transitioned to from the specified state. By default, this
         * method returns {@code true}, except the specified state is the same as this one.
         *
         * @param state the state from which the transition would occur
         * @return true if the transition is possible, false otherwise
         */
        public boolean isPossibleOrigin(BeaconingState state) {
            return !state.equals(this);
        }
    }

    /*
     * External API
     */
    /**
     * Current beaconing interval.
     * @see ul.fcul.lasige.find.beaconing.Policy.BeaconingInterval
     */
    private static Policy.BeaconingInterval sCurrentBeaconingInterval = Policy.BeaconingInterval.OFF;

    /**
     * Timestamp to query currently connected neighbors. The interval is calculated on the highest value
     * between the policy's beaconing interval (2 times this value) and 20 minutes.
     *
     * <p>This method has implications on how often neighbors are considered disconnected. Just because we
     * did not see a certain neighbor in the last couple of beaconing rounds, does not means it is not reachable.
     * Only when a neighbor isn't seen for more than the calculated time interval, then it is considered disconnected
     * (no longer reachable)</p>
     * @return Timestamp of past 20 minutes or 2 times the beaconing interval (whichever is higher).
     */
    public static long getCurrentTimestamp() {
        final int periodMillis = Math.max(2 * sCurrentBeaconingInterval.getIntervalMillis(), 20 * 60 * 1000); // last 20 minutes (max)
        return (System.currentTimeMillis() - periodMillis) / 1000;
    }

    /**
     * Timestamp to query recently connected neighbors.
     * @return Timestamp of past 24 hours.
     */
    public static long getRecentTimestamp() {
        return (System.currentTimeMillis() / 1000) - (24 * 60 * 60); // last 24 hours
    }

    /**
     * Notify all {@link ul.fcul.lasige.find.data.FullContract.Neighbors} and
     * {@link ul.fcul.lasige.find.data.FullContract.NeighborProtocols} content resolvers.
     * @param context Application context.
     */
    private void notifyNeighborUris(Context context) {
        final Uri[] neighborUris = new Uri[] {
                FullContract.Neighbors.URI_ALL,
                FullContract.Neighbors.URI_CURRENT,
                FullContract.Neighbors.URI_RECENT,
                FullContract.NeighborProtocols.URI_ALL,
                FullContract.NeighborProtocols.URI_CURRENT,
                FullContract.NeighborProtocols.URI_RECENT
        };

        ContentResolver resolver = context.getContentResolver();
        for (Uri uri : neighborUris) {
            resolver.notifyChange(uri, null);
        }
    }


}
