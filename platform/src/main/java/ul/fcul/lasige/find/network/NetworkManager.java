package ul.fcul.lasige.find.network;

import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import com.google.common.base.Optional;
import com.google.common.primitives.Ints;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ul.fcul.lasige.find.apps.TokenGenerator;

/**
 * The class provides basic mechanisms to manipulate the WiFi adapter. It implements the Singleton design
 * pattern and should be accessed through {@link NetworkManager#getInstance(Context)}.
 *
 * Created by hugonicolau on 13/11/15.
 */
public class NetworkManager {
    private static final String TAG = NetworkManager.class.getSimpleName();

    public static final String BASE_AP_NAME = "FindAP";
    private static final String ERROR_MSG_NO_AP_MANIP = "AP manipulation not available.";
    private static final String ERROR_MSG_NO_3G_MANIP = "Mobile Data manipulation not available.";

    // singleton instance
    private static NetworkManager sInstance;
    //TODO private static String sBluetoothAddressString;

    private final Context mContext;
    // WiFi lock, not reference counted
    private final WifiManager.WifiLock mWifiLock;
    //TODO private final WifiManager.MulticastLock mMulticastLock;
    // lock count
    private int mWifiConnectionLockCount;
    //TODO private int mBtConnectionLockCount;

    protected final ConnectivityManager mConnectivityManager;
    protected final WifiManager mWifiManager;
    protected final BluetoothAdapter mBluetoothAdapter;
    // network callback
    private final NetworkStateChangeReceiver mConnectivityReceiver;

    // network states
    private final Deque<NetworkManagerState> mPreviousNetworkManagerStates = new ArrayDeque<>();

    private final Optional<Method> mGetMobileDataEnabled;
    private final Optional<Method> mSetMobileDataEnabled;
    private final Optional<Method> mIsWifiApEnabled;
    private final Optional<Method> mSetWifiApEnabled;

    private WifiConfiguration mApConfig;
    private boolean mIsBtEnabling;

    /**
     * Provides access to singleton instance.
     * @param context Application context.
     * @return A {@link NetworkManager} object.
     */
    public static synchronized NetworkManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new NetworkManager(context);
            //TODO sBluetoothAddressString = ConfigurationStore.getBluetoothAddress(context);
        }
        return sInstance;
    }

    /**
     * Constructor
     * @param context Application context.
     */
    private NetworkManager(Context context) {
        mContext = context;

        // android system services
        mConnectivityManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mConnectivityReceiver = new NetworkStateChangeReceiver();

        // locks
        mWifiLock = mWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "FindWifiLock");
        mWifiLock.setReferenceCounted(false);
        /*TODO mMulticastLock = mWifiManager.createMulticastLock("FindMulticastLock");
        mMulticastLock.setReferenceCounted(false);*/

        // discover hidden WLAN AP state methods using reflection
        Class<? extends WifiManager> wmClass = mWifiManager.getClass();
        mIsWifiApEnabled = getAccessibleMethod(wmClass, "isWifiApEnabled");
        mSetWifiApEnabled = getAccessibleMethod(wmClass, "setWifiApEnabled",
                WifiConfiguration.class, Boolean.TYPE);

        if (!mIsWifiApEnabled.isPresent() || !mSetWifiApEnabled.isPresent()) {
            Log.d(TAG, ERROR_MSG_NO_AP_MANIP);
        }

        // Discover hidden 3G state methods using reflection
        Class<? extends ConnectivityManager> cmClass = mConnectivityManager.getClass();
        mGetMobileDataEnabled = getAccessibleMethod(cmClass, "getMobileDataEnabled");
        mSetMobileDataEnabled = getAccessibleMethod(cmClass, "setMobileDataEnabled", Boolean.TYPE);

        if (!mGetMobileDataEnabled.isPresent() || !mSetMobileDataEnabled.isPresent()) {
            Log.d(TAG, ERROR_MSG_NO_3G_MANIP);
        }

        // Prepare basic configuration data for access point mode
        mApConfig = createBaseApConfig(BASE_AP_NAME, WifiConfiguration.KeyMgmt.NONE);
    }

    /*
     * NETWORK STATE CHANGE NOTIFICATIONS
     */
    /**
     * Registers callback for connectivity changes.
     * @param callback Callback.
     * @see NetworkStateChangeReceiver
     */
    public void registerForConnectivityChanges(NetworkStateChangeReceiver.NetworkChangeListener callback) {
        if (!mConnectivityReceiver.hasListeners()) {
            // fist listener, register context in receiver
            mConnectivityReceiver.register(mContext);
        }
        mConnectivityReceiver.registerListener(callback);

        // WiFi connectivity changes are done using sticky broadcasts, so the callback will receive
        // the latest state change directly after registering here. This does not apply to Bluetooth
        // state changes, so we do that manually here.
        //TODO callback.onBluetoothAdapterChanged(isBluetoothEnabled());
    }

    /**
     * Unregisters callback for connectivity changes.
     * @param callback Callback
     */
    public void unregisterForConnectivityChanges(NetworkStateChangeReceiver.NetworkChangeListener callback) {
        mConnectivityReceiver.unregisterListener(callback);
        if (!mConnectivityReceiver.hasListeners()) {
            // last listener, unregister context in receiver
            mConnectivityReceiver.unregister(mContext);
        }
    }

    /*
     * SAVEPOINTS
     */

    /**
     * Retrieves the number of savepoint stored in the {@link NetworkManager}.
     * @return Number of savepoints.
     */
    public int getSavepointCount() {
        return mPreviousNetworkManagerStates.size();
    }

    /**
     * Captures and stores the current state of the NetManager. Such a savepoint offers an easy way
     * to undo all changes done to the networking configuration on the device. Creating a savepoint
     * should be done before the first call which alters the NetManager's state (i.e., one of the
     * setXYZ methods).
     *
     * @see NetworkManager#rollback()
     */
    public void createSavepoint() {
        // get state
        NetworkManagerState currentState = NetworkManagerState.captureCurrentState(this);
        // store it
        mPreviousNetworkManagerStates.push(currentState);
        Log.v(TAG, String.format("Created savepoint %d: %s", mPreviousNetworkManagerStates.size(), currentState));
    }

    /**
     * Restores the networking configuration to a previously captured state. Use this at the end of
     * the NetManager's lifecycle, i.e. before shutting down everything.
     *
     * @see NetworkManager#createSavepoint()
     */
    public void rollback() {
        if (mPreviousNetworkManagerStates.size() == 0) {
            throw new IllegalStateException("There is no savepoint to rollback to: create one first.");
        }

        NetworkManagerState.restorePreviousState(this, mPreviousNetworkManagerStates.pop());
        Log.v(TAG, String.format("Rollback to savepoint %d", mPreviousNetworkManagerStates.size()));
    }

    /*
     * LOCKS
     */
    /**
     * Acquire WiFi locks. These are not reference counted, so acquiring multiple times is safe: If it is
     * already acquired, nothing happens.
     */
    public void acquireLocks() {
        mWifiLock.acquire();
        //TODO mMulticastLock.acquire();
    }

    /**
     * Release WiFi locks. These are not reference counted, so releasing multiple times is safe: The first call
     * to release() will actually release the lock, the others get ignored.
     */
    public void releaseLocks() {
        mWifiLock.release();
        //TODO mMulticastLock.release();
    }

    /**
     * Reset all connection locks maintaining current lock state.
     */
    public void resetConnectionLocks() {
        Log.v(TAG, String.format("Resetting connection locks: WiFi %d->0, Bluetooth %d->0",
                mWifiConnectionLockCount, 0/*TODO mBtConnectionLockCount*/));
        mWifiConnectionLockCount = 0;
        //TODO mBtConnectionLockCount = 0;
    }

    // WIFI MANAGEMENT
    /**
     * Describes the current state of the WiFi adapter.
     */
    public enum WifiState {
        /**
         * The WiFi adapter is currently not connected to any network.
         */
        DISCONNECTED,
        /**
         * The WiFi adapter is currently connected to a public network.
         */
        STA_ON_PUBLIC_AP,
        /**
         * The WiFi adapter is currently connected to another FIND device in access point mode.
         */
        STA_ON_FIND_AP,
        /**
         * This device is currently in access point mode, possibly serving other FIND clients.
         */
        FIND_AP
    }

    /**
     * Returns whether the WiFi connectivity exists.
     * @return true if connectivity exists, false otherwise.
     */
    @SuppressWarnings("deprecation")
    public boolean isWifiConnected() {
        final NetworkInfo networkInfo = mConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return networkInfo.isConnected();
    }

    /**
     * Checks whether a connection exist with a given network.
     * @param network Network.
     * @return true if a connection exists with network, false otherwise.
     */
    private boolean isWifiConnected(ScanResult network) {
        return isWifiConnected() && network.SSID.equals(NetworkManager.unquoteSSID(mWifiManager.getConnectionInfo().getSSID()))
                && network.BSSID.equals(mWifiManager.getConnectionInfo().getBSSID());
        //return wifiConfiguration.status == WifiConfiguration.Status.CURRENT; ORIGINAL
    }

    /**
     * Returns the current state of the WiFi adapter.
     *
     * @return one of {@link WifiState#DISCONNECTED DISCONNECTED},
     *         {@link WifiState#STA_ON_PUBLIC_AP STA_ON_PUBLIC_AP},
     *         {@link WifiState#STA_ON_FIND_AP STA_ON_FIND_AP} or {@link WifiState#FIND_AP
     *         FIND_AP}.
     */
    public WifiState getWifiState() {
        WifiState currentState = WifiState.DISCONNECTED;

        if (isApEnabled().or(false)) {
            currentState = WifiState.FIND_AP;
        }

        if (isWifiConnected()) {
            final String ssid = unquoteSSID(mWifiManager.getConnectionInfo().getSSID());
            if (ssid.startsWith(BASE_AP_NAME)) {
                currentState = WifiState.STA_ON_FIND_AP;
            } else {
                currentState = WifiState.STA_ON_PUBLIC_AP;
            }
        }

        return currentState;
    }

    /**
     * Finds the network interface that is used for WiFi.
     *
     * @return the WiFi network interface.
     */
    public synchronized Optional<NetworkInterface> getWifiNetworkInterface() {
        NetworkInterface wifiInterface = null;

        try {
            // get all network interfaces
            final List<NetworkInterface> networkInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());

            // for each of the interfaces
            for (NetworkInterface iface : networkInterfaces) {
                final String name = iface.getName();
                if (name.equals("wlan0") || name.equals("eth0") || name.equals("wl0.1")) {
                    // it is a wifi network interface!
                    wifiInterface = iface;
                    break;
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "Error while getting network interfaces", e);
        }

        return Optional.fromNullable(wifiInterface);
    }

    /**
     * Retrieves a list of previously configured (known) networks.
     * @return List of known networks.
     */
    public List<WifiConfiguration> getConfiguredWifiNetworks() {
        final List<WifiConfiguration> networks = mWifiManager.getConfiguredNetworks();
        return (networks == null ? new ArrayList<WifiConfiguration>() : networks);
    }

    /**
     * Generates a list of up to 256 continuous IPv4 addresses in the subnet currently connected to,
     * by incrementing the least significant byte of the IP address from the subnet's start address.
     *
     * @return a list of InetAddress objects
     */
    public List<InetAddress> getIp4SweepRange() {
        final List<InetAddress> addresses = new ArrayList<>();

        DhcpInfo dhcp = mWifiManager.getDhcpInfo();
        // NOTE: netmask and ipAddress are little-endian, but we want big-endian
        final byte[] netmask = Ints.toByteArray(Integer.reverseBytes(dhcp.netmask));
        final byte[] ownIp = Ints.toByteArray(Integer.reverseBytes(dhcp.ipAddress));

        byte[] baseAddress = {
                (byte) (ownIp[0] & netmask[0]),
                (byte) (ownIp[1] & netmask[1]),
                (byte) (ownIp[2] & netmask[2]),
                (byte) (ownIp[3] & netmask[3])
        };

        // Stop when reaching end of subnet or after at most 255 addresses, whichever comes first
        do {
            ++baseAddress[3];

            if (baseAddress[3] != ownIp[3]) {
                // Skip own address
                try {
                    addresses.add(InetAddress.getByAddress(baseAddress));
                } catch (UnknownHostException e) {
                    // Skip
                }
            }
        } while ((baseAddress[3] | netmask[3]) != (byte) 0xFF);

        return addresses;
    }

    /**
     * Start WiFi scan. This will trigger an asynchronous callback to {@link ScanResultsReceiver.ScanResultsListener#onWifiScanCompleted()}
     * @return true if scan started, false otherwise.
     */
    public boolean initiateWifiScan() {
        Log.d(TAG, "Initiated WiFi scan");
        return mWifiManager.startScan();
    }

    /**
     * Retrieves a list of networks originated from a WiFi scan.
     * @return A {@link ScanResults} object.
     * @see ScanResults
     */
    public ScanResults getScanResults() {
        return new ScanResults(mWifiManager.getScanResults(), getConfiguredWifiNetworks());
    }

    /**
     * Attempts to connect to a given network. Disconnects from current network if needed.
     * @param network Network
     * @return true if connection with network already existed, false otherwise.
     */
    public boolean connectToWifi(ScanResult network) {
        Log.v(TAG, "Requested to connect to network " + network.SSID + " / " + network.BSSID);

        // check if network is already configured
        int networkId = -1;
        for (WifiConfiguration wifiConfiguration : getConfiguredWifiNetworks()) {
            if (network.SSID.equals(NetworkManager.unquoteSSID(wifiConfiguration.SSID))) {
                if (wifiConfiguration.BSSID == null || wifiConfiguration.BSSID.equals(network.BSSID)) {
                    if (isWifiConnected(network)) {
                        // already connected to the requested network
                        Log.d(TAG, "already connected");
                        return true;
                    }
                    networkId = wifiConfiguration.networkId;
                }
            }
        }

        Log.d(TAG, "Network configuration: " + networkId);
        if (networkId < 0) {
            // It's not configured, so add new configuration before connecting
            // NOTE: The assumption here is that the requested network is open.
            WifiConfiguration wifiConfig = new WifiConfiguration();
            wifiConfig.SSID = '\"' + network.SSID + '\"';
            wifiConfig.BSSID = network.BSSID;
            wifiConfig.priority = 1;
            wifiConfig.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
            wifiConfig.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            wifiConfig.status = WifiConfiguration.Status.DISABLED;

            networkId = mWifiManager.addNetwork(wifiConfig);

            final NetworkManagerState savepoint = mPreviousNetworkManagerStates.peek();
            if (savepoint != null) {
                savepoint.addTemporaryWifiId(networkId);
            }
        }

        mWifiManager.disconnect();
        mWifiManager.enableNetwork(networkId, true);
        mWifiManager.reconnect();
        return false;
    }

    /**
     * Checks if the WiFi adapter is enabled.
     *
     * @return true if adapter is enabled, false otherwise
     */
    public boolean isWifiEnabled() {
        return mWifiManager.isWifiEnabled();
    }

    /**
     * Enables/disables the WiFi adapter.
     *
     * @return true if operation was successful (or the adapter was already in the requested state)
     */
    public boolean setWifiEnabled(boolean enabled) {
        if (enabled != mWifiManager.isWifiEnabled()) {
            return mWifiManager.setWifiEnabled(enabled);
        }
        return true;
    }

    /**
     * Returns the information about the current WiFi connection.
     *
     * @return an {@link Optional} containing the current {@link WifiConnection} info if present
     */
    public Optional<WifiConnection> getCurrentConnection() {
        WifiConnection connection = null;

        final WifiState state = getWifiState();
        final Optional<NetworkInterface> iface = getWifiNetworkInterface();
        try {
            if (state.equals(WifiState.FIND_AP)) {
                connection = WifiConnection.fromApMode(iface.get(), mApConfig.SSID);
            } else if (!state.equals(WifiState.DISCONNECTED)) {
                connection = WifiConnection.fromStaMode(
                        iface.get(), mWifiManager.getDhcpInfo(), mWifiManager.getConnectionInfo());
            }
        } catch(Exception e)
        {
            // in case we can't get WifiNetworkInterface
            Log.e(TAG, "Couldn't get WifiNetworkInterface");
        }

        return Optional.fromNullable(connection);
    }

    // WIFI AP MANAGEMENT
    /**
     * Checks whether access point mode manipulation methods are available for this device or not.
     *
     * @return true if access point mode can be manipulated, false otherwise.
     */
    public boolean isApAvailable() {
        return (mIsWifiApEnabled.isPresent() && mSetWifiApEnabled.isPresent());
    }

    /**
     * Checks whether access point mode is activated or not, failing silently if this can not be
     * determined.
     *
     * @return true if the access point mode is activated, false if it is deactivated, or null upon
     *         failure retrieving this information
     */
    public Optional<Boolean> isApEnabled() {
        Optional<Boolean> isEnabled = Optional.absent();
        if (mIsWifiApEnabled.isPresent()) {
            try {
                isEnabled = Optional.of(
                        (Boolean) mIsWifiApEnabled.get().invoke(mWifiManager));
            } catch (IllegalAccessException
                    | IllegalArgumentException
                    | InvocationTargetException e) {
                // Do nothing, fall through
            }
        }
        return isEnabled;
    }

    /**
     * Checks whether access point mode is activated or not, throwing an exception if this can not
     * be determined.
     *
     * @return true if the access point mode is activated, false if it is deactivated
     * @throws IllegalAccessException if the access point state can not be determined
     */
    public boolean isApEnabledOrThrow() throws IllegalAccessException {
        Optional<Boolean> isEnabled = isApEnabled();
        if (!isEnabled.isPresent()) {
            throw new IllegalAccessException(ERROR_MSG_NO_AP_MANIP);
        }
        return isEnabled.get();
    }

    /**
     * Enables/Disables the access point mode of the WLAN adapter, reporting errors to do so instead
     * of throwing an exception.
     *
     * @param enabled true to enable the access point mode, false to disable it
     * @return an Optional containing true if the operation was successful, false if it wasn't, or
     *         an absent value if changing the state is not possible on this device
     */
    public Optional<Boolean> setApEnabled(boolean enabled) {
        Optional<Boolean> success = Optional.absent();
        if (mSetWifiApEnabled.isPresent()) {
            final boolean wifiEnabledBefore = isWifiEnabled();
            if (enabled && wifiEnabledBefore) {
                // Deactivate WiFi adapter to allow switching to AP mode
                setWifiEnabled(false);
            }

            try {
                success = Optional.of(
                        (Boolean) mSetWifiApEnabled.get()
                                .invoke(mWifiManager, mApConfig, enabled));
            } catch (IllegalAccessException
                    | IllegalArgumentException
                    | InvocationTargetException e) {
                // Restore previous wifi state
                setWifiEnabled(wifiEnabledBefore);
            }
        }
        return success;
    }

    /**
     * Enables/Disables the access point mode of the WLAN adapter, throwing an exception if this is
     * not possible on this device.
     *
     * @param enabled true to enable the access point mode, false to disable it
     * @return true if operation was successful, false otherwise
     * @throws IllegalAccessException if access point mode can not be activated on this device
     */
    public boolean setApEnabledOrThrow(boolean enabled) throws IllegalAccessException {
        Optional<Boolean> success = setApEnabled(enabled);
        if (!success.isPresent()) {
            throw new IllegalAccessException(ERROR_MSG_NO_AP_MANIP);
        }
        return success.get();
    }

    /**
     * Builds a {@link WifiConfiguration} object that is composed by a given base name and key.
     * @param baseApName Base name.
     * @param keyMgmt Key.
     * @return A {@link WifiConfiguration} object.
     */
    private WifiConfiguration createBaseApConfig(String baseApName, int keyMgmt) {
        WifiConfiguration baseWifiConfig = new WifiConfiguration();
        baseWifiConfig.SSID = baseApName + "-" + TokenGenerator.generateToken(4);
        baseWifiConfig.allowedKeyManagement.set(keyMgmt);
        return baseWifiConfig;
    }

    /*
     * UTILS
     */
    /**
     * Checks if there is Internet access with the current connection.
     * @return true if Internet is accessible via current connection, false otherwise.
     */
    public boolean hasInternetAccess() {
        NetworkInfo netInfo = mConnectivityManager.getActiveNetworkInfo();
        //should check null because in air plan mode it will be null
        if(netInfo != null && netInfo.isConnected()) {
            // try to ping
            return ping();
        }
        return false;
    }

    /**
     * Pings google's dns.
     * @return true if operation was sucessful, false otherwise.
     */
    private boolean ping() {
        Runtime runtime = Runtime.getRuntime();
        try
        {
            Process  mIpAddrProcess = runtime.exec("/system/bin/ping -c 1 8.8.8.8"); // google dns
            int mExitValue = mIpAddrProcess.waitFor();
            System.out.println("mExitValue "+mExitValue);
            if(mExitValue==0){
                // YES!
                return true;
            }else{
                return false;
            }
        }
        catch (InterruptedException ignore)
        {
            ignore.printStackTrace();
            System.out.println(" Exception:"+ignore);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            System.out.println(" Exception:"+e);
        }
        return false;
    }

    /**
     * Creates a secure Access Point configuration with a given SSID and password.
     * @param ssid SSID.
     * @param password Password.
     */
    public void createSecureApConfig(String ssid, String password) {
        if (ssid == null) {
            throw new NullPointerException("SSID must not be null.");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 digits long.");
        }

        mApConfig = createBaseApConfig(ssid, 4); // WifiConfiguration.KeyMgmt.WPA2_PSK is hidden
        mApConfig.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
        mApConfig.preSharedKey = password;
    }

    /**
     * Retrieves the current Access Point configuration.
     *
     * @return A {@link WifiConfiguration} object.
     */
    public Optional<WifiConfiguration> getApConfiguration() {
        WifiConfiguration apConfig = null;

        // use reflection to get method
        final Optional<Method> getApConfig = getAccessibleMethod(mWifiManager.getClass(), "getWifiApConfiguration");

        if (getApConfig.isPresent()) {
            // success
            try {
                // get config
                apConfig = (WifiConfiguration) getApConfig.get().invoke(mWifiManager);
            } catch (IllegalAccessException
                    | IllegalArgumentException
                    | InvocationTargetException e) {
                // Do nothing, fall through
            }
        }

        return Optional.fromNullable(apConfig);
    }

    /**
     * Sets Access Point configuration with a given {@link WifiConfiguration} object.
     * @param apConfig Access Point configuration.
     * @return true if successful, false otherwise.
     */
    public boolean setApConfiguration(Optional<WifiConfiguration> apConfig) {
        if (apConfig.isPresent()) {
            // use reflection to get method
            final Optional<Method> setApConfig = getAccessibleMethod(
                    mWifiManager.getClass(), "setWifiApConfiguration", WifiConfiguration.class);

            if (setApConfig.isPresent()) {
                // success
                try {
                    // set configuration
                    return (Boolean) setApConfig.get().invoke(mWifiManager, apConfig.get());
                } catch (IllegalAccessException
                        | IllegalArgumentException
                        | InvocationTargetException e) {
                    // Do nothing, fall through
                }
            }
        }
        return false;
    }

    /**
     * Checks whether the device can turn into an access point for tethering.
     *
     * @return true if the device can act as access point, false otherwise.
     */
    public boolean hasApCapabilities() {
        Optional<WifiConfiguration> apConfig = getApConfiguration();
        if (apConfig.isPresent()) {
            if (apConfig.get() != null && apConfig.get().allowedKeyManagement.length() != 0) {
                // A Nexus One that has never used tethering will have length 0
                // Maybe need to find a better way to figure out if config valid
                return true;
            }
        }
        return false;
    }

    // MOBILE DATA (3G) MANIPULATION
    /**
     * Checks whether mobile data is enabled or not, failing silently if this can't be determined.
     *
     * @return true if mobile data is enabled, false if it is disabled, or null upon failure to
     *         retrieve this information
     */
    public Optional<Boolean> isMobileDataEnabled() {
        Optional<Boolean> isEnabled = Optional.absent();
        if (mGetMobileDataEnabled.isPresent()) {
            try {
                isEnabled = Optional.of(
                        (Boolean) mGetMobileDataEnabled.get().invoke(mConnectivityManager));
            } catch (IllegalArgumentException
                    | InvocationTargetException
                    | IllegalAccessException e) {
                // Do nothing, let it skip through
            }
        }
        return isEnabled;
    }

    /**
     * Checks whether mobile data is enabled or not, and throws an exception if this can't be
     * determined.
     *
     * @return true if mobile data is enabled, false if it is disabled
     * @throws IllegalAccessException if the mobile data state can not be determined
     */
    public boolean isMobileDataEnabledOrThrow() throws IllegalAccessException {
        Optional<Boolean> isEnabled = isMobileDataEnabled();
        if (!isEnabled.isPresent()) {
            throw new IllegalAccessException(ERROR_MSG_NO_3G_MANIP);
        }
        return isEnabled.get();
    }

    /**
     * Enables/disables mobile data using reflected method of ConnectivityManager, reporting errors
     * instead of throwing exceptions.
     *
     * @param enabled true to enable mobile data, false to disable
     * @return true if the underlying method was accessible, false otherwise
     */
    private boolean setMobileDataEnabledOrReportFailure(boolean enabled) {
        boolean failed = true;
        if (mSetMobileDataEnabled.isPresent()) {
            try {
                mSetMobileDataEnabled.get().invoke(mConnectivityManager, enabled);
                failed = false;
            } catch (IllegalAccessException
                    | IllegalArgumentException
                    | InvocationTargetException e) {
                // Do nothing, let it skip through
            }
        }
        return failed;
    }

    /**
     * Enables/disables mobile data, and fails silently if setting this is not possible.
     *
     * @param enabled true to enable mobile data, false to disable
     */
    public void setMobileDataEnabled(boolean enabled) {
        setMobileDataEnabledOrReportFailure(enabled);
    }

    /**
     * Enables/disables mobile data, and throws an exception on failure to do so.
     *
     * @param enabled true to enable mobile data, false to disable
     * @throws IllegalAccessException if device does not allow mobile data manipulation.
     */
    public void setMobileDataEnabledOrThrow(boolean enabled) throws IllegalAccessException {
        if (setMobileDataEnabledOrReportFailure(enabled)) {
            throw new IllegalAccessException(ERROR_MSG_NO_3G_MANIP);
        }
    }

    // BLUETOOTH

   /*TODO public boolean isBluetoothAvailable() {
        return (mBluetoothAdapter != null);
    }

    public boolean isBluetoothEnabled() {
        if (mBluetoothAdapter != null) {
            return mBluetoothAdapter.isEnabled();
        }
        return false;
    }

    public boolean isBluetoothEnabling() {
        return mIsBtEnabling;
    }

    public Optional<String> getBluetoothAddress() {
        if (sBluetoothAddressString == null) {
            if (mBluetoothAdapter != null) {
                final String btMac = mBluetoothAdapter.getAddress();
                if (btMac != null) {
                    ConfigurationStore.saveBluetoothAddress(mContext, btMac);
                    sBluetoothAddressString = btMac;
                }
            }
        }
        return Optional.fromNullable(sBluetoothAddressString);
    }

    public Optional<ByteString> getBluetoothAddressAsBytes() {
        ByteString btAddressBytes = null;

        final Optional<String> btAddressString = getBluetoothAddress();
        if (btAddressString.isPresent()) {
            btAddressBytes = ByteString.copyFrom(parseMacAddress(sBluetoothAddressString));
        }

        return Optional.fromNullable(btAddressBytes);
    }

    public boolean setBluetoothEnabled(boolean enable) {
        if (mBluetoothAdapter != null) {
            mIsBtEnabling = enable;

            final boolean isEnabled = mBluetoothAdapter.isEnabled();
            if (enable && !isEnabled) {
                return mBluetoothAdapter.enable();
            } else if (!enable && isEnabled) {
                return mBluetoothAdapter.disable();
            }
            return true;
        }
        return false;
    }

    public boolean doBluetoothScan(boolean start) {
        if (mBluetoothAdapter != null) {
            if (start) {
                return mBluetoothAdapter.startDiscovery();
            } else {
                return mBluetoothAdapter.cancelDiscovery();
            }
        }
        return false;
    }

    public void requestBluetoothDiscoverable() {
        if (mBluetoothAdapter != null) {
            final int scanMode = mBluetoothAdapter.getScanMode();
            if (scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                final Intent discoverableIntent =
                        new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 0);
                discoverableIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                mContext.startActivity(discoverableIntent);
            }
        }
    }

    public BluetoothServerSocket getBluetoothServerSocket(String name, UUID uuid)
            throws IOException {
        BluetoothServerSocket btServerSocket = null;
        if (mBluetoothAdapter != null) {
            btServerSocket =
                    mBluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(name, uuid);
        }
        return btServerSocket;
    }

    public BluetoothDevice getBluetoothDevice(byte[] btAddress) {
        BluetoothDevice btDevice = null;
        if (mBluetoothAdapter != null) {
            final String btAddressString = unparseMacAddress(btAddress);
            if (BluetoothAdapter.checkBluetoothAddress(btAddressString)) {
                btDevice = mBluetoothAdapter.getRemoteDevice(btAddressString);
            }
        }
        return btDevice;
    }
*/
    // HELPERS

    /**
     * Uses reflection to retrieve a method from a class, whose accessible flag is set to true.
     *
     * @param cls the class on which to search for the method
     * @param method the name of the method to look for
     * @param signature the signature of the method, as needed by the reflection system
     * @return the method, if found on the class, or null otherwise
     */
    private static Optional<Method> getAccessibleMethod(
            Class<?> cls, String method, Class<?>... signature) {
        Method reflectedMethod = null;
        try {
            reflectedMethod = cls.getDeclaredMethod(method, signature);
            reflectedMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "Error while retrieving " + method + " via reflection:", e);
        }
        return Optional.fromNullable(reflectedMethod);
    }

    /**
     * Utility to remove quotation marks around SSID strings as returned by Android.
     *
     * @param ssid The SSID string to clean.
     * @return the ssid without quotation marks around it
     * @see WifiInfo#getSSID()
     */
    public static String unquoteSSID(String ssid) {
        return ssid.replaceAll("^\"|\"$", "");
    }

    /**
     * Parses a hexadecimal MAC address string to individual bytes.
     *
     * @param macAddress the ':'-delimited mac address string
     * @return the big-endian mac address byte array
     */
    public static byte[] parseMacAddress(String macAddress) {
        final String[] parts = macAddress.split(":");
        final int len = parts.length;
        assert (len == 6 || len == 8);

        final byte[] parsedBytes = new byte[len];
        for (int i = 0; i < len; i++) {
            final Integer hex = Integer.parseInt(parts[i], 16);
            parsedBytes[i] = hex.byteValue();
        }

        return parsedBytes;
    }

    /**
     * Creates an hexadecimal MAC address string from individual bytes.
     * @param macAddress Mac address as byte array.
     * @return Hexadecial mac address.
     */
    public static String unparseMacAddress(byte[] macAddress) {
        if (macAddress == null || macAddress.length == 0) {
            return null;
        }

        final StringBuilder addr = new StringBuilder();
        for (byte b : macAddress) {
            if (addr.length() > 0) {
                addr.append(":");
            }
            addr.append(String.format("%02x", b));
        }
        return addr.toString().toUpperCase(Locale.US);
    }

    /**
     * THIS METHOD IS ALWAYS RETURNIN FALSE;
     * Determines whether a WiFi network is open (unencrypted).
     *
     * @param network the result of a network scan
     * @return true if the network is open
     */
    public static boolean isOpenNetwork(ScanResult network) {
        /*if (network.capabilities != null) {
            if (network.capabilities.equals("") || network.capabilities.equals("[ESS]")) {
                return true;
            }
        }*/
        return false;
    }

    /**
     * Determines whether an SSID belongs to an FIND access point.
     *
     * @param ssid the SSID of the network in question
     * @return true if the SSID belongs to an FIND network
     */
    public static boolean isFindSSID(String ssid) {
        if(ssid == null) return false;
        return unquoteSSID(ssid).startsWith(BASE_AP_NAME);
    }

    /**
     * Set of Phone Models that do not receive Multicast/Broadcast packets when the screen is off.
     */
    private static final Set<String> sRxMulticastModels = new HashSet<>();
    static {
        sRxMulticastModels.add("Nexus One");
        sRxMulticastModels.add("HTC Desire");
    }

    public static boolean deviceSupportsMulticastWhenAsleep() {
        return sRxMulticastModels.contains(Build.MODEL);
    }
}
