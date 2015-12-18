package ul.fcul.lasige.find.beaconing;

import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.wifi.ScanResult;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.google.common.base.Optional;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.network.NetworkManager;
import ul.fcul.lasige.find.network.NetworkStateChangeReceiver;
import ul.fcul.lasige.find.network.ScanResults;
import ul.fcul.lasige.find.network.ScanResultsReceiver;
import ul.fcul.lasige.find.network.WifiConnection;
import ul.fcul.lasige.find.utils.InterruptibleFailsafeRunnable;

/**
 * The class extends {@link InterruptibleFailsafeRunnable} and is responsible for finding and connect to neighbors.
 * To do that, WiFi connectivity is frequently changed based on current policy.
 *
 * The class deals with each beaconing period, which is triggered by the {@link ul.fcul.lasige.find.service.SupervisorService}.
 * Each of beaconing period has several beaconing slots. In each slot this thread performs a set of actions trying to
 * connect to a neighbor.
 *
 * @see ul.fcul.lasige.find.service.SupervisorService
 * @see BeaconingManager
 * @see Policy
 *
 * Created by hugonicolau on 12/11/15.
 */
public class BeaconingIntervalHandler extends InterruptibleFailsafeRunnable
        implements NetworkStateChangeReceiver.NetworkChangeListener, ScanResultsReceiver.ScanResultsListener {
    private static final String TAG = BeaconingIntervalHandler.class.getSimpleName();

    /**
     * Maximum beacon duration, 1 minute. After this time, the beaconing period ends.
     */
    public static final int MAX_BEACONING_DURATION = 60 * 1000; // 1 minute

    /**
     * Timeout each beaconing slot, 15 seconds
     */
    private static final int WIFI_SWITCH_TIMEOUT = 15 * 1000; // 15 seconds
    /**
     * Maximum number of beaconing slots, 4
     */
    private static final int WIFI_MAX_ITERATION_COUNT = MAX_BEACONING_DURATION / WIFI_SWITCH_TIMEOUT;
    /**
     * Maximum number of slots staying as access point.
     */
    private static final int WIFI_MAX_AP_COUNT = 2;

    /*private static final int BT_INITIAL_WAIT_TIMEOUT = 20 * 1000;
    private static final int BT_ITERATION_INTERVAL = 5 * 1000;
    private static final int BT_MAX_ITERATION_COUNT =
            (MAX_BEACONING_DURATION - BT_INITIAL_WAIT_TIMEOUT) / BT_ITERATION_INTERVAL;
*/

    /**
     * WiFi Beaconing States: {@link WifiBeaconingState#DISABLED}, {@link WifiBeaconingState#ENABLING},
     * {@link WifiBeaconingState#ENABLED}, {@link WifiBeaconingState#SCANNING}, {@link WifiBeaconingState#CONNECTING},
     * {@link WifiBeaconingState#CONNECTED}, {@link WifiBeaconingState#AP_ENABLING}, {@link WifiBeaconingState#AP_ENABLED},
     * {@link WifiBeaconingState#DISABLING}, {@link WifiBeaconingState#FINISHED}.
     */
    private enum WifiBeaconingState {
        DISABLED, ENABLING, ENABLED, SCANNING, CONNECTING, CONNECTED, AP_ENABLING, AP_ENABLED,
        DISABLING, FINISHED
    }

    /**
     * Not being used
     */
    private enum BtBeaconingState {
        DISABLED, ENABLING, ENABLED, SCANNING, CONNECTED, DISABLING, FINISHED
    }

    // current wifi state
    private WifiBeaconingState mWifiBeaconingState = WifiBeaconingState.DISABLED;
    // current bluetooth state - not being used
    private BtBeaconingState mBtBeaconingState = BtBeaconingState.DISABLED;

    // name of networks we attempted to connect - this is used to connect to different networks
    // on each beaconing slot
    private final Multiset<String> mAttemptedNetworks = HashMultiset.create();
    // name of visited networks - this is used to connect to different networks on each
    // beaconing slot
    private final Deque<String> mVisitedNetworks = new ArrayDeque<>();
    // discovered bluetooth devices - not being used
    private final List<BluetoothDevice> mDiscoveredBtDevices = new ArrayList<>();

    // beaconing period id
    private final int mBeaconingId;
    // beaconing manager
    private final BeaconingManager mBeaconingManager;
    // power manager
    private final PowerManager mPowerManager;
    // network manager
    private final NetworkManager mNetManager;

    // broadcast receiver for WiFi scan results
    private ScanResultsReceiver mScanReceiver;

    // handler for when beaconing slots end
    private Handler mHandler;
    // timestamp of beaconing period start
    private long mTimeStarted;

    // number of executed beaconing slots
    private int mWifiIterationCount;
    private int mBtIterationCount;
    // number of beaconing slots as AP
    private int mApIterations;
    // boolean that indicates if we are still connecting to a network
    private boolean mIsWifiStillConnecting;

    /**
     * Constructor.
     * @param beaconingManager Beaconing manager.
     * @param beaconingId Beaconing period id.
     */
    public BeaconingIntervalHandler(BeaconingManager beaconingManager, int beaconingId) {
        super(TAG);
        mBeaconingId = beaconingId;
        mBeaconingManager = beaconingManager;
        // get power manager
        mPowerManager = beaconingManager.mPowerManager;
        // get network manager
        mNetManager = beaconingManager.mNetManager;
    }

    /**
     * Main thread.
     */
    @Override
    protected void execute() {
        // set start time
        mTimeStarted = System.currentTimeMillis();
        // set thread as lopper and prepare handler
        Looper.prepare();
        mHandler = new Handler();

        // start beaconing, this may include turning network adapters ON
        activateNetworks();

        // schedule beaconing slot handler and wait for next iteration
        mWifiIterationCount++;
        mHandler.postDelayed(new WifiIterationTimeoutHandler(), WIFI_SWITCH_TIMEOUT); // in 15 seconds
        //TODO mHandler.postDelayed(new BluetoothIterationTimeoutHandler(), BT_INITIAL_WAIT_TIMEOUT);

        // start loop
        Looper.loop();
    }

    /**
     * In case of error
     * @param e Exception
     */
    @Override
    protected void onFailure(Throwable e) {
        // terminate thread
        terminate(false);
    }

    /**
     * Turns network adapters ON (if needed), and starts beaconing period.
     */
    private void activateNetworks() {
        if (mScanReceiver != null) {
            // this method was previously called by the thread, ignore
            return;
        }

        // register for connectivity changes
        // NOTE: The beaconing manager is unregistered here, and not when this beaconing period
        // was created. This is more precise, as the thread here may have some starting delay, and
        // handing over control should be seamless.
        mNetManager.registerForConnectivityChanges(this);
        mNetManager.unregisterForConnectivityChanges(mBeaconingManager);

        // start listening for scan result events
        mScanReceiver = new ScanResultsReceiver(this);
        mScanReceiver.register(mBeaconingManager.mContext);

        // setup Bluetooth beaconing state
        /*if (!mNetManager.isBluetoothEnabled()) {
            if (canUseFeatureNow(Policy.Feature.BLUETOOTH)) {
                // We can use bluetooth, but need to enable it first.
                mBtBeaconingState = BtBeaconingState.ENABLING;
                mNetManager.setBluetoothEnabled(true);
            } else {
                setBtBeaconingFinished();
            }
        } else {
            mBtBeaconingState = BtBeaconingState.ENABLING;
            onBluetoothAdapterChanged(true);
        }*/

        // save current network state
        mNetManager.createSavepoint();

        // disable 3G
        // TODO this can be changed in the future
        mNetManager.setMobileDataEnabled(false);

        // setup WiFi beaconing state
        if (mNetManager.isWifiConnected()) {
            // WiFi is already connected, and we can start using it
            mWifiBeaconingState = WifiBeaconingState.CONNECTING;
            // connected!
            onWifiNetworkChanged(true, false);
        } else {
            // we're not connected, but maybe we can change that?
            if (canUseFeatureNow(Policy.Feature.WIFI_CLIENT)) {
                // yes, we can. We may need to switch it on first, though.
                mWifiBeaconingState = WifiBeaconingState.ENABLING;
                Log.d(TAG, "Enablind WiFi");
                if (mNetManager.isWifiEnabled()) {
                    // it is enabled!
                    onWifiAdapterChanged(true);
                } else {
                    // enable it
                    mNetManager.setWifiEnabled(true);
                }
            } else {
                // this call starts AP mode if possible
                if (!startApModeIfPossible()) {
                    // there is no way for us to get a WiFi connection by ourselves
                    setWifiBeaconingFinished();
                }
            }
        }
    }

    /**
     * Restores previously saved network state when this beaconing period started.
     */
    private void restoreNetworks() {
        // unregister receivers
        mScanReceiver.unregister(mBeaconingManager.mContext);
        mNetManager.unregisterForConnectivityChanges(this);

        // rollback
        mNetManager.rollback();

        // NOTE: Don't re-register the beaconing manager for connectivity changes. This is done by
        // the beaconing manager himself, as it depends on certain other factors which this handler
        // does not (need to/want to) know.
    }

    /**
     * End this thread.
     * @param aborted Was it aborted?
     */
    private void terminate(final boolean aborted) {
        if (mHandler == null) {
            // handler has already been terminated
            return;
        }

        // terminate in the next iteration of the thread looper, ignoring all other posted messages
        mHandler.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                // total beaconing period time
                final long runtime = (System.currentTimeMillis() - mTimeStarted) / 1000;
                Log.d(TAG, "Terminating beacon burst after " + runtime + " seconds.");

                // clear message queue
                mHandler.removeCallbacksAndMessages(null);

                // update state
                mWifiBeaconingState = WifiBeaconingState.DISABLING;
                //TODO mBtBeaconingState = BtBeaconingState.DISABLING;

                // stop sending beacons
                mBeaconingManager.stopBeaconSenders();

                // if there are no locks, restore network to previous state
                if (!mBeaconingManager.isInternetLocked() && !mBeaconingManager.isWifiConnectionLocked()) {
                    restoreNetworks();
                }

                // quit looper
                mHandler.getLooper().quit();
                mHandler = null;

                // notify internet callbacks
                mBeaconingManager.notifyInternetCallbacks();

                if (!aborted) {
                    // we are finishing the beaconing period normally, notify BeaconingManager
                    mBeaconingManager.onBeaconingIntervalFinished(mBeaconingId);
                }
            }
        });
    }

    /**
     * Returns the number of visited networks during the beaconing period.
     * @return Number of visited networks.
     */
    private int visitedNetworksCount() {
        final HashSet<String> visitedNetworksSet = new HashSet<>(mVisitedNetworks);
        return visitedNetworksSet.size();
    }

    /**
     * Checks if the screen is ON and whether a given policy can be used in foreground.
     * @param policy Policy
     * @return true if the screen is ON and the policy can't run in foreground, false otherwise.
     */
    @SuppressWarnings("deprecation")
    private boolean isBotheringUser(Policy policy) {
        return mPowerManager.isScreenOn() && !policy.allows(Policy.Feature.FOREGROUND); //TODO original: && false
    }

    /**
     * Returns whether the current beaconing policy supports a given feature.
     * @param feature Feature.
     * @return true if the policy supports the feature, false otherwise.
     */
    private boolean canUseFeature(Policy.Feature feature) {
        return mBeaconingManager.mPolicy.allows(feature);
    }

    /**
     * Returns whether a feature can be used right now, considering that the user may be actively using
     * the device.
     * @param feature Feature.
     * @return true if the feature can be used, false otherwise.
     */
    private boolean canUseFeatureNow(Policy.Feature feature) {
        Policy policy = mBeaconingManager.mPolicy;
        return policy!= null && policy.allows(feature) && !isBotheringUser(policy);
    }

    /**
     * Set the beaconing state to {@link WifiBeaconingState#FINISHED FINISHED}.
     */
    private void setWifiBeaconingFinished() {
        mWifiBeaconingState = WifiBeaconingState.FINISHED;
        //TODO if (mBtBeaconingState == BtBeaconingState.FINISHED) {
            // Finished as well - we can stop everything
            terminate(false);
        //TODO}
    }

   /*private void setBtBeaconingFinished() {
        mBtBeaconingState = BtBeaconingState.FINISHED;
        if (mWifiBeaconingState == WifiBeaconingState.FINISHED) {
            // Finished as well - we can stop everything
            terminate(false);
        }
    }*/

    /**
     * Returns a connectible network that was not visited in this beaconing period.
     * If all networks have been visited then it returns the first one.
     * @param scanResults WiFi scan results
     * @return Network to connect to.
     * @see ScanResults#getConnectibleNetworks()
     */
    private ScanResult choseRandomNetworkToConnect(ScanResults scanResults) {
        // get random connectible network we did not visit before
        ScanResult selectedNetwork = null;
        for (ScanResult network : scanResults.getConnectibleNetworks()) {
            if (!mVisitedNetworks.contains(network.SSID)) {
                selectedNetwork = network;
                break;
            }
        }
        if (selectedNetwork == null) {
            // visited all other networks before, so stick to the first one
            final ScanResult firstNetwork = scanResults.getConnectibleNetworks().get(0);
            // but only if it's FIND (removed because an open network can have Internet access
            /*if (NetworkManager.isFindSSID(firstNetwork.SSID)) {
                selectedNetwork = firstNetwork;
            }*/
        }

        return selectedNetwork;
    }

    /**
     * Attempts to switch to Access Point mode and returns whether it was successful.
     * @return true if it was able to switch to AP mode, false otherwise.
     */
    private boolean startApModeIfPossible() {
        if (canUseFeatureNow(Policy.Feature.WIFI_AP) && (mApIterations < WIFI_MAX_AP_COUNT)) {
            // we can use AP mode and we still didn't reach the max number of iteractions in AP mode
            mNetManager.createSavepoint();
            // switch to ap mode
            if (mNetManager.setApEnabled(true).or(false)) {
                // update beaconing state
                mWifiBeaconingState = WifiBeaconingState.AP_ENABLING;
                // stop sending beacons to neighbors, instead waits to receive them
                mBeaconingManager.stopWifiSender();
                // update number of ap mode iterations
                mApIterations++;
                return true;
            }
            Log.v(TAG, "Could not switch to AP mode");
            mNetManager.rollback();
        }
        return false;
    }

    /**
     * Stops Access Point mode by stopping beacon senders and receivers in the {@link BeaconingManager}. It also finishes
     * this beaconing period.
     * @see BeaconingManager#stopWifiReceiver()
     * @see BeaconingManager#stopWifiSender()
     */
    private void stopApMode() {
        Log.v(TAG, "Finishing AP mode after " + mApIterations + " rounds as AP");
        mNetManager.setApEnabled(false);
        mBeaconingManager.stopWifiSender();
        mBeaconingManager.stopWifiReceiver();

        // NOTE: This automatically reactivates WiFi, if it was on before, and
        // through the state change callbacks the normal WiFi operation mode
        // kicks in again.
        mNetManager.rollback();

        // AP mode is always the "last resort", so when stopping AP mode, wifi beaconing should also
        // be stopped.
        setWifiBeaconingFinished();
    }

    /**
     * Callback for WiFi adapter changes.
     *
     * <p>When enabled starts scanning for networks.</p>
     *
     * @param enabled Whether WiFi adapter is ON.
     */
    @Override
    public void onWifiAdapterChanged(boolean enabled) {
        if (enabled && mWifiBeaconingState == WifiBeaconingState.ENABLING) {
            mWifiBeaconingState = WifiBeaconingState.ENABLED;
            mNetManager.initiateWifiScan();
            mWifiBeaconingState = WifiBeaconingState.SCANNING;
        }
    }

    /**
     * Callback for WiFi scan finished.
     *
     * <p>Chooses a random network and connects to it. If no networks are available then switch to AP mode.
     * These operations (network switching) only take place when the policy allows it and there are no current locks.</p>
     */
    @Override
    public void onWifiScanCompleted() {

        if (mBeaconingManager.isInternetLocked() || mBeaconingManager.isWifiConnectionLocked() ||
                !canUseFeatureNow(Policy.Feature.WIFI_CLIENT)) {
            // Switching is not possible right now
            return;
        }
        if (mWifiBeaconingState != WifiBeaconingState.SCANNING
                && mWifiBeaconingState != WifiBeaconingState.CONNECTED) {
            return;
        }

        // get scan results and chose network to connect to (or switch to AP mode)
        final ScanResults scanResults = mNetManager.getScanResults();
        if (scanResults.hasConnectibleNetworks()) {
            // get network
            ScanResult selectedNetwork = choseRandomNetworkToConnect(scanResults);
            if (selectedNetwork != null) {
                // update wifi state
                mWifiBeaconingState = WifiBeaconingState.CONNECTING;
                // update attempted networks
                mAttemptedNetworks.add(selectedNetwork.SSID);

                // we try to connect to a random network
                if (!mNetManager.connectToWifi(selectedNetwork)) {
                    // we had to switch networks
                    Log.v(TAG, String.format("Switching to network '%s'", selectedNetwork.SSID));
                    // stop senders/receivers on current network when switching to new network
                    mBeaconingManager.stopWifiReceiver();
                    mBeaconingManager.stopWifiSender();
                }
                else {
                    // we are already connected to selected network
                    mWifiBeaconingState = WifiBeaconingState.CONNECTED;
                    onNetworkConnected();
                }
                // notify client internet observers
                mBeaconingManager.notifyInternetCallbacks();
                return;
            }
        }
        else {Log.d(TAG, "No connectable networks found");}

        // no networks available

        // try ap mode
        if (!startApModeIfPossible() && mWifiIterationCount > 2) {
            // no networks and can't start ap mode, there's nothing else we can do
            // let's finished beaconing period and wait for better luck next round
            Log.v(TAG, "Scan returned no new networks, finishing wifi beaconing");
            setWifiBeaconingFinished();
        } else {
            // either we were able to start ap mode or there's still a change we might be able to do it
            // in the next beaconing slot
            Log.v(TAG, String.format("Staying on network '%s'", mVisitedNetworks.peek()));
        }
    }

    /**
     * Callback for WiFi network changes
     * @param connected Whether it is connected.
     * @param isFailover Whether it was triggered by a failover
     */
    @Override
    public void onWifiNetworkChanged(boolean connected, boolean isFailover) {
        // check Internet connection and notify observers
        mBeaconingManager.notifyInternetCallbacks();

        if (connected && mWifiBeaconingState == WifiBeaconingState.CONNECTING) {
            // we were connecting
            // update beaconing state
            mWifiBeaconingState = WifiBeaconingState.CONNECTED;
            // we are connect, act accordingly
            onNetworkConnected();
        } else if (!connected && mWifiBeaconingState == WifiBeaconingState.CONNECTED) {
            // we were disconnected
            // stop receivers
            mBeaconingManager.stopWifiReceiver();

            if (NetworkManager.isFindSSID(mVisitedNetworks.peek()) && mBeaconingManager.mIsDesignatedAp
                    && startApModeIfPossible()) {
                // previous AP node went offline, and we are supposed to take over, which is what
                // we're doing
                return;
            }

            // if not becoming AP, scan for other networks to connect to
            mWifiBeaconingState = WifiBeaconingState.SCANNING;
            mNetManager.initiateWifiScan();
        }
    }

    /**
     * Call back for AP mode changes
     * @param activated Whether AP mode is active
     */
    @Override
    public void onAccessPointModeChanged(boolean activated) {
        // check Internet connection and notify observers
        mBeaconingManager.notifyInternetCallbacks();

        if (activated && mWifiBeaconingState == WifiBeaconingState.AP_ENABLING) {
            // we are currently in AP mode
            mWifiBeaconingState = WifiBeaconingState.AP_ENABLED;
            // connected! act accordingly
            onNetworkConnected();
        }
    }

    /**
     * Perform actions related to a new network connection, including starting beacon receivers and senders.
     * @return true if we are still connected, false otherwise.
     * @see BeaconingManager#startWifiReceiver()
     * @see BeaconingManager#startWifiSender(boolean)
     */
    private boolean onNetworkConnected() {
        Optional<WifiConnection> connection = mNetManager.getCurrentConnection();
        if (!connection.isPresent()) {
            // This rarely happens when the wifi timeout handler already switched to another network
            // or has been shut down at the same time a connection attempt succeeded.
            Log.v(TAG, "Connection already lost again");
            return false;
        }

        mBeaconingManager.startWifiReceiver();
        mBeaconingManager.startWifiSender(true);

        mVisitedNetworks.push(connection.get().getNetworkName().get());

        return true;
    }

    @Override
    public void onBluetoothAdapterChanged(boolean enabled) {
        /*TODO if (enabled && mBtBeaconingState == BtBeaconingState.ENABLING) {
            mBtBeaconingState = BtBeaconingState.SCANNING;
            mBeaconingManager.mNetManager.doBluetoothScan(true);
            mBeaconingManager.startBluetoothReceiver();
        }*/
    }

    @Override
    public void onBluetoothDeviceFound(BluetoothDevice btDevice) {
        // probably happens while the scan is still in progress -> collect for later usage
        //TODO mDiscoveredBtDevices.add(btDevice);
    }

    @Override
    public void onBluetoothScanCompleted() {
        // now start connecting to discovered devices
        /*TODOmBtBeaconingState = BtBeaconingState.CONNECTED;
        for (BluetoothDevice btDevice : mDiscoveredBtDevices) {
            mBeaconingManager.connectToBtDevice(btDevice);
        }
        mDiscoveredBtDevices.clear();

        // recent neighbors too
        mHandler.post(new RfcommSender(mBeaconingManager));*/
    }

    /*
     * TIMEOUT HANDLERS
     */

    /**
     * Thread was interrupted. If thread still exists, then try to terminate as an abortion.
     * @see BeaconingIntervalHandler#terminate(boolean)
     */
    @Override
    public void interrupt() {
        // the usual check for a set interrupt flag interferes with the looper used, therefore an
        // interrupt from outside directly terminates this handler.
        if (mThread != null) {
            terminate(true);
        } else {
            super.interrupt();
        }
    }

    /**
     * Runnable that handles all beaconing slots timeout. It is responsible for bootstrapping next
     * beaconing slot's actions.
     * @see BeaconingIntervalHandler#WIFI_SWITCH_TIMEOUT
     */
    private class WifiIterationTimeoutHandler implements Runnable {
        /**
         * Main thread.
         */
        @Override
        public void run() {
            if (mWifiIterationCount >= WIFI_MAX_ITERATION_COUNT) {
                // its time to finish, too many rounds (slots)
                if (mWifiBeaconingState == WifiBeaconingState.AP_ENABLING
                        || mWifiBeaconingState == WifiBeaconingState.AP_ENABLED) {
                    stopApMode();
                }
                terminate(false);
                return;
            }

            // there's still time for more beaconing slots

            // Get amount of neighbors found in this round
            int neighborCount = 0;
            final String lastNetworkName = mVisitedNetworks.peek();
            if (lastNetworkName != null) {
                // get current neighbors (the ones found in this beaconing round)
                // this is an approximation (heuristic)
                Set<Neighbor> neighbors = mBeaconingManager.mDbController.getNeighbors(BeaconingManager.getCurrentTimestamp());
                neighborCount = neighbors.size();
            }

            Log.d(TAG, String.format(
                    "Iteration %d finished ('found' %d neighbors), initiating next one",
                    mWifiIterationCount, neighborCount));

            // Bootstrap next iteration
            if (!mBeaconingManager.isInternetLocked() && !mBeaconingManager.isWifiConnectionLocked()) {
                switch (mWifiBeaconingState) {
                    case CONNECTING:
                    case ENABLING:
                    case ENABLED:
                    case CONNECTED: {
                        if (mWifiBeaconingState == WifiBeaconingState.CONNECTING && !mIsWifiStillConnecting) {
                            // let it connect, for one more slot
                            Log.v(TAG, "Still connecting");
                            mIsWifiStillConnecting = true;
                            mBeaconingManager.notifyInternetCallbacks();
                            break;
                        }
                        mIsWifiStillConnecting = false;

                        // Don't stay too long on the same network
                        if ((neighborCount == 0) || (visitedNetworksCount() <= mWifiIterationCount - 1)) {
                            /*if (lastNetworkName != null && NetworkManager.isFindSSID(lastNetworkName)) {
                                // Connecting/connected to FIND network - stay there
                                break;
                            }*/

                            if (canUseFeature(Policy.Feature.WIFI_AP)) {
                                // we can use ap mode
                                final double pEnableAp = canUseFeature(Policy.Feature.WIFI_CLIENT) ? // TODO ?? why do we go to AP mode if WIFI_CLIENT is supported? shouldn't it be the other way around?
                                        1 : -0.1 + (mWifiIterationCount * 0.3);
                                if (Math.random() <= pEnableAp && startApModeIfPossible()) {
                                    Log.v(TAG, "Switching to AP mode");
                                    break;
                                }
                            }

                            if (canUseFeature(Policy.Feature.WIFI_CLIENT)) {
                                // try other networks
                                mWifiBeaconingState = WifiBeaconingState.SCANNING;
                                mNetManager.initiateWifiScan();
                                Log.v(TAG, "Scanning for more networks");
                            } else {
                                Log.v(TAG, "Can't connect to networks, finishing wifi beaconing");
                                setWifiBeaconingFinished();
                            }
                        }
                        break;
                    }
                    case SCANNING: {
                        // Already (or still) scanning, wait for the results
                        break;
                    }
                    case AP_ENABLING: {
                        // Keep waiting, but not forever
                        if (mApIterations >= WIFI_MAX_AP_COUNT) {
                            stopApMode();
                        } else {
                            Log.v(TAG, "Still enabling AP mode");
                            mApIterations++;
                        }
                        break;
                    }

                    case AP_ENABLED: {
                        if ((mApIterations >= WIFI_MAX_AP_COUNT) && (neighborCount == 0)) {
                            // No neighbors found? Deactivate again.
                            stopApMode();
                        } else {
                            mApIterations++;
                        }
                        break;
                    }
                    default: {
                        break;
                    }
                }
            }

            // update beaconing slot count
            mWifiIterationCount++;
            // schedule next timeout
            mHandler.postDelayed(this, WIFI_SWITCH_TIMEOUT);
        }
    }

    /*private class BluetoothIterationTimeoutHandler implements Runnable {
        @Override
        public void run() {
            if (mBtIterationCount >= BT_MAX_ITERATION_COUNT) {
                terminate(false);
                return;
            }

            if (mBtBeaconingState == BtBeaconingState.CONNECTED) {
                final Collection<BluetoothSocket> btSockets =
                        mBeaconingManager.mBluetoothSockets.values();

                if (btSockets.size() == 0 && !mBeaconingManager.isBtConnectionLocked()) {
                    Log.v(TAG, "Finishing bluetooth beaconing");
                    setBtBeaconingFinished();
                    return;
                }
            }

            mBtIterationCount++;
            mHandler.postDelayed(this, BT_ITERATION_INTERVAL);
        }
    }*/

}

