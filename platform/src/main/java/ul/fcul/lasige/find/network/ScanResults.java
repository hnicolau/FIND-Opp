package ul.fcul.lasige.find.network;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Class that represents WiFi scan results.
 *
 * Created by hugonicolau on 13/11/15.
 */
public class ScanResults {
    // networks
    private final List<ScanResult> mAvailableNetworks = new ArrayList<>();

    /**
     * Constructor. Builds a list of available networks that include all FIND platform Access Points,
     * open networks, and known (configured) networks. List is sorted by signal strength. FIND APs have priority,
     * followed by open networks, and known networks.
     * @param availableNetworks List of scanned networks.
     * @param configuredNetworks List of configured (known) networks.
     */
    public ScanResults(List<ScanResult> availableNetworks, List<WifiConfiguration> configuredNetworks) {

        final Set<String> knownSSIDs = new HashSet<>(configuredNetworks.size());
        for (final WifiConfiguration network : configuredNetworks) {
            knownSSIDs.add(NetworkManager.unquoteSSID(network.SSID));
        }

        for (final ScanResult network : availableNetworks) {
            if (NetworkManager.isFindSSID(network.SSID)
                    || (NetworkManager.isOpenNetwork(network) && network.level > -90) // TODO open networks are not being used for now
                    || knownSSIDs.contains(network.SSID)) {
                mAvailableNetworks.add(network);
            }
        }

        Collections.sort(mAvailableNetworks, new SignalStrengthSorter());
    }

    /**
     * Checks whether there are available networks.
     * @return true if there are one or more connectible networks, false otherwise.
     */
    public boolean hasConnectibleNetworks() {
        return (mAvailableNetworks.size() > 0);
    }

    /**
     * Retrieves a list of available networks.
     * @return A list of available networks
     * @see ScanResult
     */
    public List<ScanResult> getConnectibleNetworks() {
        return mAvailableNetworks;
    }

    /**
     * Comparator. If networks are of the same type, then compares signal strength. Otherwise, FIND
     * APs have priority, followed by open networks, and known networks.
     */
    private class SignalStrengthSorter implements Comparator<ScanResult> {
        @Override
        public int compare(ScanResult lhs, ScanResult rhs) {
            final int lhsNetworkType;
            if (NetworkManager.isFindSSID(lhs.SSID)) {
                lhsNetworkType = 1;
            } else if (NetworkManager.isOpenNetwork(lhs)) {
                lhsNetworkType = 2;
            } else {
                lhsNetworkType = 3;
            }

            final int rhsNetworkType;
            if (NetworkManager.isFindSSID(rhs.SSID)) {
                rhsNetworkType = 1;
            } else if (NetworkManager.isOpenNetwork(rhs)) {
                rhsNetworkType = 2;
            } else {
                rhsNetworkType = 3;
            }

            if (lhsNetworkType == rhsNetworkType) {
                return (0 - lhs.level + rhs.level);
            } else {
                return (lhsNetworkType - rhsNetworkType);
            }
        }
    }
}
