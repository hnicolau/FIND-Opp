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
 * Created by hugonicolau on 12/11/15.
 */
public class BeaconingIntervalHandler extends InterruptibleFailsafeRunnable
        implements NetworkStateChangeReceiver.NetworkChangeListener, ScanResultsReceiver.ScanResultsListener {
    private static final String TAG = BeaconingIntervalHandler.class.getSimpleName();

    public static final int MAX_BEACONING_DURATION = 60 * 1000; // 1 minute

    private static final int WIFI_SWITCH_TIMEOUT = 15 * 1000; // 15 seconds
    private static final int WIFI_MAX_ITERATION_COUNT = MAX_BEACONING_DURATION / WIFI_SWITCH_TIMEOUT;
    private static final int WIFI_MAX_AP_COUNT = 2;

    /*private static final int BT_INITIAL_WAIT_TIMEOUT = 20 * 1000;
    private static final int BT_ITERATION_INTERVAL = 5 * 1000;
    private static final int BT_MAX_ITERATION_COUNT =
            (MAX_BEACONING_DURATION - BT_INITIAL_WAIT_TIMEOUT) / BT_ITERATION_INTERVAL;
*/
    private static enum WifiBeaconingState {
        DISABLED, ENABLING, ENABLED, SCANNING, CONNECTING, CONNECTED, AP_ENABLING, AP_ENABLED,
        DISABLING, FINISHED;
    }

    private static enum BtBeaconingState {
        DISABLED, ENABLING, ENABLED, SCANNING, CONNECTED, DISABLING, FINISHED;
    }

    private WifiBeaconingState mWifiBeaconingState = WifiBeaconingState.DISABLED;
    private BtBeaconingState mBtBeaconingState = BtBeaconingState.DISABLED;

    private final Multiset<String> mAttemptedNetworks = HashMultiset.create();
    private final Deque<String> mVisitedNetworks = new ArrayDeque<>();
    private final List<BluetoothDevice> mDiscoveredBtDevices = new ArrayList<>();

    private final int mBeaconingId;
    private final BeaconingManager mBeaconingManager;

    private final PowerManager mPowerManager;
    private final NetworkManager mNetManager;

    private ScanResultsReceiver mScanReceiver;
    private Handler mHandler;
    private long mTimeStarted;
    private long mTimeNetworkConnected;

    private int mWifiIterationCount;
    private int mBtIterationCount;
    private int mApIterations;
    private boolean mIsWifiStillConnecting;

    public BeaconingIntervalHandler(BeaconingManager beaconingManager, int beaconingId) {
        super(TAG);
        mBeaconingId = beaconingId;
        mBeaconingManager = beaconingManager;

        mPowerManager = beaconingManager.mPowerManager;
        mNetManager = beaconingManager.mNetManager;
    }

    @Override
    protected void execute() {
        mTimeStarted = System.currentTimeMillis();
        Looper.prepare();
        mHandler = new Handler();

        activateNetworks();

        // schedule and wait for next iteration
        mWifiIterationCount++;
        mHandler.postDelayed(new WifiIterationTimeoutHandler(), WIFI_SWITCH_TIMEOUT);
        //TODO mHandler.postDelayed(new BluetoothIterationTimeoutHandler(), BT_INITIAL_WAIT_TIMEOUT);

        Looper.loop();
    }

    @Override
    protected void onFailure(Throwable e) {
        terminate(false);
    }

    private void activateNetworks() {
        if (mScanReceiver != null) {
            return;
        }

        // Register for connectivity changes
        // NOTE: The beaconing manager is unregistered here, and not when this beaconing interval
        // was created. This is more precise, as the thread here may have some starting delay, and
        // handing over control should be seamless.
        mNetManager.registerForConnectivityChanges(this);
        mNetManager.unregisterForConnectivityChanges(mBeaconingManager);

        // Start listening for scan result events
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

        mNetManager.createSavepoint();

        // Disable 3G
        mNetManager.setMobileDataEnabled(false);

        // Setup WiFi beaconing state
        if (mNetManager.isWifiConnected()) {
            // WiFi is already connected, and we can start using it
            mWifiBeaconingState = WifiBeaconingState.CONNECTING;
            onWifiNetworkChanged(true, false);
        } else {
            // We're not connected, but maybe we can change that?
            if (canUseFeatureNow(Policy.Feature.WIFI_CLIENT)) {
                // Yes, we can. We may need to switch it on first, though.
                mWifiBeaconingState = WifiBeaconingState.ENABLING;
                Log.d(TAG, "Enablind WiFi");
                if (mNetManager.isWifiEnabled()) {
                    onWifiAdapterChanged(true);
                } else {
                    mNetManager.setWifiEnabled(true);
                }
            } else {
                if (!startApModeIfPossible()) {
                    // there is no way for us to get a WiFi connection by ourselves
                    setWifiBeaconingFinished();
                }
            }
        }
    }

    private void restoreNetworks() {
        mScanReceiver.unregister(mBeaconingManager.mContext);

        mNetManager.unregisterForConnectivityChanges(this);
        mNetManager.rollback();

        // NOTE: Don't re-register the beaconing manager for connectivity changes. This is done by
        // the beaconing manager himself, as it depends on certain other factors which this handler
        // does not (need to/want to) know.
    }

    private void terminate(final boolean aborted) {
        if (mHandler == null) {
            // Handler has already been terminated
            return;
        }

        mHandler.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                final long runtime = (System.currentTimeMillis() - mTimeStarted) / 1000;
                Log.d(TAG, "Terminating beacon burst after " + runtime + " seconds.");

                // Clear message queue
                mHandler.removeCallbacksAndMessages(null);

                mWifiBeaconingState = WifiBeaconingState.DISABLING;
                //TODO mBtBeaconingState = BtBeaconingState.DISABLING;

                mBeaconingManager.stopBeaconSenders();

                if (!mBeaconingManager.isInternetLocked() && !mBeaconingManager.isWifiConnectionLocked()) {
                    restoreNetworks();
                }

                mHandler.getLooper().quit();
                mHandler = null;

                mBeaconingManager.notifyInternetCallbacks();

                if (!aborted) {
                    mBeaconingManager.onBeaconingIntervalFinished(mBeaconingId);
                }
            }
        });
    }

    private int visitedNetworksCount() {
        final HashSet<String> visitedNetworksSet = new HashSet<>(mVisitedNetworks);
        return visitedNetworksSet.size();
    }

    private boolean isBotheringUser(Policy policy) {
        return (mPowerManager.isScreenOn() && !policy.allows(Policy.Feature.FOREGROUND)) && false; //TODO wtf &&false?
    }

    private boolean canUseFeature(Policy.Feature feature) {
        return mBeaconingManager.mPolicy.allows(feature);
    }

    private boolean canUseFeatureNow(Policy.Feature feature) {
        Policy policy = mBeaconingManager.mPolicy;
        if(policy == null) return false;
        return (policy.allows(feature) && !isBotheringUser(policy));
    }

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

    private ScanResult choseRandomNetworkToConnect(ScanResults scanResults) {
        // get random open network we did not visit before
        ScanResult selectedNetwork = null;
        for (ScanResult network : scanResults.getConnectibleNetworks()) {
            if (!mVisitedNetworks.contains(network.SSID)) {
                selectedNetwork = network;
                break;
            }
        }
        if (selectedNetwork == null) {
            // Visited all other networks before, so stick to the first one
            final ScanResult firstNetwork = scanResults.getConnectibleNetworks().get(0);
            // but only if it's FIND (removed because an open network can have Internet access
            /*if (NetworkManager.isFindSSID(firstNetwork.SSID)) {
                selectedNetwork = firstNetwork;
            }*/
        }

        return selectedNetwork;
    }

    private boolean startApModeIfPossible() {
        if (canUseFeatureNow(Policy.Feature.WIFI_AP) && (mApIterations < WIFI_MAX_AP_COUNT)) {
            mNetManager.createSavepoint();
            if (mNetManager.setApEnabled(true).or(false)) {
                mWifiBeaconingState = WifiBeaconingState.AP_ENABLING;
                mBeaconingManager.stopWifiSender();
                mApIterations++;
                return true;
            }
            Log.v(TAG, "Could not switch to AP mode");
            mNetManager.rollback();
        }
        return false;
    }

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

    @Override
    public void onWifiAdapterChanged(boolean enabled) {
        if (enabled && mWifiBeaconingState == WifiBeaconingState.ENABLING) {
            mWifiBeaconingState = WifiBeaconingState.ENABLED;
            mNetManager.initiateWifiScan();
            mWifiBeaconingState = WifiBeaconingState.SCANNING;
        }
    }

    @Override
    public void onWifiScanCompleted() {

        if (mBeaconingManager.isInternetLocked() || mBeaconingManager.isWifiConnectionLocked() || !canUseFeatureNow(Policy.Feature.WIFI_CLIENT)) {
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
            ScanResult selectedNetwork = choseRandomNetworkToConnect(scanResults);
            if (selectedNetwork != null) {
                mWifiBeaconingState = WifiBeaconingState.CONNECTING;
                mAttemptedNetworks.add(selectedNetwork.SSID);

                if (!mNetManager.connectToWifi(selectedNetwork)) {
                    Log.v(TAG, String.format("Switching to network '%s'", selectedNetwork.SSID));

                    // Stop senders/receivers on current network when switching to new network
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

        if (!startApModeIfPossible() && mWifiIterationCount > 2) {
            // Found no suitable network to connect - stop wifi beaconing
            Log.v(TAG, "Scan returned no new networks, finishing wifi beaconing");
            setWifiBeaconingFinished();
        } else {
            Log.v(TAG, String.format("Staying on network '%s'", mVisitedNetworks.peek()));
        }
    }

    @Override
    public void onWifiNetworkChanged(boolean connected, boolean isFailover) {
        // check Internet connection and notify observers
        mBeaconingManager.notifyInternetCallbacks();

        if (connected && mWifiBeaconingState == WifiBeaconingState.CONNECTING) {
            mWifiBeaconingState = WifiBeaconingState.CONNECTED;
            onNetworkConnected();
        } else if (!connected && mWifiBeaconingState == WifiBeaconingState.CONNECTED) {
            mBeaconingManager.stopWifiReceiver();

            if (NetworkManager.isFindSSID(mVisitedNetworks.peek())
                    && mBeaconingManager.mIsDesignatedAp
                    && startApModeIfPossible()) {
                // Previous AP node went offline, and we are supposed to take over, which is what
                // we're doing
                return;
            }

            // If not becoming AP, scan for other networks to connect to
            mWifiBeaconingState = WifiBeaconingState.SCANNING;
            mNetManager.initiateWifiScan();
        }
    }

    @Override
    public void onAccessPointModeChanged(boolean activated) {
        // check Internet connection and notify observers
        mBeaconingManager.notifyInternetCallbacks();

        if (activated && mWifiBeaconingState == WifiBeaconingState.AP_ENABLING) {
            mWifiBeaconingState = WifiBeaconingState.AP_ENABLED;
            onNetworkConnected();
        }
    }

    private boolean onNetworkConnected() {
        Optional<WifiConnection> connection = mNetManager.getCurrentConnection();
        if (!connection.isPresent()) {
            // This rarely happens when the wifi timeout handler already switched to another network
            // or has been shut down at the same time a connection attempt succeeded.
            Log.v(TAG, "Connection already lost again");
            return false;
        }

        mTimeNetworkConnected = System.currentTimeMillis() / 1000;

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

    private class WifiIterationTimeoutHandler implements Runnable {
        @Override
        public void run() {
            if (mWifiIterationCount >= WIFI_MAX_ITERATION_COUNT) {
                // its time to finish, too many rounds
                if (mWifiBeaconingState == WifiBeaconingState.AP_ENABLING
                        || mWifiBeaconingState == WifiBeaconingState.AP_ENABLED) {
                    stopApMode();
                }
                terminate(false);
                return;
            }

            // Get amount of neighbors found in last round
            int neighborCount = 0;
            final String lastNetworkName = mVisitedNetworks.peek();
            if (lastNetworkName != null) {
                // get current neighbors (the ones found in this beaconing stage)
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
                            // Let it connect
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
                                final double pEnableAp = canUseFeature(Policy.Feature.WIFI_CLIENT) ?
                                        1 : -0.1 + (mWifiIterationCount * 0.3);
                                if (Math.random() <= pEnableAp && startApModeIfPossible()) {
                                    Log.v(TAG, "Switching to AP mode");
                                    break;
                                }
                            }

                            if (canUseFeature(Policy.Feature.WIFI_CLIENT)) {
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

            mWifiIterationCount++;
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

