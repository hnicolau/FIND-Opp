package ul.fcul.lasige.find.network;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.google.common.base.Objects;
import com.google.common.base.Optional;

import java.util.HashSet;
import java.util.Set;

/**
 * Class represents a WiFi state.
 *
 * Created by hugonicolau on 13/11/15.
 */
public class NetworkManagerState {
    private static final String TAG = NetworkManagerState.class.getSimpleName();

    // is data enabled?
    private final Optional<Boolean> mDataWasEnabled;
    // is wifi enabled?
    private final boolean mWifiWasEnabled;
    // wifi id
    private final int mConnectedWifiId;
    // ap mode enabled?
    private final boolean mApWasEnabled;
    // ap configuration
    private final Optional<WifiConfiguration> mApConfiguration;
    // bluetooth enabled?
    private final boolean mBluetoothWasEnabled;

    private final Set<Integer> mTemporaryWifiIds;

    /**
     * Constructor. A network state is represented by the constructor parameters.
     * @param dataEnabled Data state.
     * @param wifiEnabled WiFi state.
     * @param apEnabled Ap state.
     * @param apConfig Ap configuration.
     * @param btEnabled Bluetooth state.
     * @param connectedWifiId WiFi id of connection.
     */
    private NetworkManagerState(Optional<Boolean> dataEnabled, boolean wifiEnabled,
                                boolean apEnabled, Optional<WifiConfiguration> apConfig,
                                boolean btEnabled, int connectedWifiId) {

        mDataWasEnabled = dataEnabled;
        mWifiWasEnabled = wifiEnabled;
        mApWasEnabled = apEnabled;
        mApConfiguration = apConfig;
        mBluetoothWasEnabled = btEnabled;
        mConnectedWifiId = connectedWifiId;

        // initialized temporary wifi ids
        mTemporaryWifiIds = new HashSet<>();
    }

    /**
     * Returns whether data was enabled.
     * @return true if data connection was enabled, false otherwise.
     */
    public Optional<Boolean> wasDataEnabled() {
        return mDataWasEnabled;
    }

    /**
     * Returns whether WiFi was enabled.
     * @return true if WiFi was enabled, false otherwise.
     */
    public boolean wasWifiEnabled() {
        return mWifiWasEnabled;
    }

    /**
     * Returns whether Access Point mode was enabled.
     * @return true if AP mode was enabled, false otherwise.
     */
    public boolean wasApEnabled() {
        return mApWasEnabled;
    }

    /**
     * Retrieves Access Point configuration.
     * @return A {@link WifiConfiguration} object.
     */
    public Optional<WifiConfiguration> getApConfig() {
        return mApConfiguration;
    }

    /**
     * Returns whether Bluetooth was enabled.
     * @return true if Bluetooth was enabled, false otherwise.
     */
    public boolean wasBluetoothEnabled() {
        return mBluetoothWasEnabled;
    }

    /**
     * Returns the ID of WiFi connection.
     * @return ID of WiFi connection.
     */
    public int getConnectedWifiId() {
        return mConnectedWifiId;
    }

    /**
     * Adds a temporary WiFi ID.
     * @param id Temporary WiFi ID.
     */
    public void addTemporaryWifiId(int id) {
        mTemporaryWifiIds.add(id);
    }

    /**
     * Retrieves a set of previously added temporary IDs
     * @return Set of temporary IDs.
     */
    public Set<Integer> getTemporaryWifiIds() {
        return mTemporaryWifiIds;
    }

    /**
     * Retrives a textual representation of a network state.
     * @return textual representation of a network state.
     */
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("wifi", mWifiWasEnabled)
                .add("to", mConnectedWifiId)
                .add("bt", mBluetoothWasEnabled)
                .add("3g", mDataWasEnabled)
                .toString();
    }

    /**
     * Utility method that captures current network state and build a {@link NetworkManagerState} object.
     * @param netManager Network manager
     * @return A {@link NetworkManagerState} object.
     */
    public static NetworkManagerState captureCurrentState(NetworkManager netManager) {
        // get data state
        final Optional<Boolean> dataEnabled = netManager.isMobileDataEnabled();
        // get bluetooth state
        final boolean btEnabled = false; //TODO (netManager.isBluetoothEnabling() || netManager.isBluetoothEnabled());
        // get wifi state
        final boolean wifiEnabled = netManager.isWifiEnabled();
        // get ap mode state
        final boolean apEnabled = netManager.isApEnabled().or(false);
        // get ap config
        final Optional<WifiConfiguration> apConfig = netManager.getApConfiguration();
        // get wifi id
        final int connectedWifiId = netManager.mWifiManager.getConnectionInfo().getNetworkId();

        return new NetworkManagerState(dataEnabled, wifiEnabled, apEnabled, apConfig, btEnabled, connectedWifiId);
    }

    /**
     * Transition to a given network state.
     * @param netManager Network manager.
     * @param previousState New network state.
     */
    public static void restorePreviousState(NetworkManager netManager, NetworkManagerState previousState) {
        final int previouslyConnectedNetworkId = previousState.getConnectedWifiId();
        final Set<Integer> temporaryNetworkIds = previousState.getTemporaryWifiIds();
        Log.v(TAG, "Temporarily created " + temporaryNetworkIds.size() + " networks");

        // De-/Reactivate WLAN adapter and clean up configuration
        netManager.setApEnabled(previousState.wasApEnabled());
        netManager.setApConfiguration(previousState.getApConfig());

        final WifiManager wifiManager = netManager.mWifiManager;
        synchronized (wifiManager) {
            if (temporaryNetworkIds.contains(wifiManager.getConnectionInfo().getNetworkId())) {
                // We're still connected to a temporary network
                wifiManager.disconnect();
            }

            // Reset WLAN configuration to previously known networks (remove temporary ones)
            for (final WifiConfiguration config : netManager.getConfiguredWifiNetworks()) {
                final int networkId = config.networkId;

                if (temporaryNetworkIds.contains(networkId)) {
                    wifiManager.disableNetwork(networkId);
                    if (!wifiManager.removeNetwork(networkId)) {
                        Log.w(TAG, "Could not remove temporary network " + config.SSID);
                    }
                } else {
                    wifiManager.enableNetwork(networkId, false);
                }
            }

            // If WiFi was enabled and connected before, let the adapter try to reconnect
            netManager.setWifiEnabled(previousState.wasWifiEnabled());
            if (previouslyConnectedNetworkId >= 0) {
                wifiManager.reconnect();
            }
        }

        // De-/Reactivate mobile data connection
        final Optional<Boolean> mobileDataWasEnabled = previousState.wasDataEnabled();
        if (mobileDataWasEnabled.isPresent()) {
            netManager.setMobileDataEnabled(mobileDataWasEnabled.get());
        }

        // De-/Reactivate bluetooth state
        //TODO netManager.setBluetoothEnabled(previousState.wasBluetoothEnabled());
    }
}
