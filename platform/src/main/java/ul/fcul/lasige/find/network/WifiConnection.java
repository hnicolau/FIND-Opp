package ul.fcul.lasige.find.network;

import android.net.DhcpInfo;
import android.net.wifi.WifiInfo;

import com.google.common.base.Optional;
import com.google.common.net.InetAddresses;
import com.google.common.primitives.Ints;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;

/**
 * Represents a WiFi connection.
 *
 * Created by hugonicolau on 13/11/15.
 *
 */
public class WifiConnection {
    private final Optional<Inet4Address> mIp4Address;
    private final Optional<Inet6Address> mIp6Address;
    private final Optional<InetAddress> mApAddress;
    private final Optional<String> mNetworkName;
    private final NetworkInterface mWifiInterface;

    /**
     * Creates a {@link WifiConnection} object when connected to a network.
     * @param iface Network interface
     * @param dhcp DHCP
     * @param connection WiFi connection
     * @return A {@link WifiConnection} object.
     */
    public static WifiConnection fromStaMode(NetworkInterface iface, DhcpInfo dhcp, WifiInfo connection) {

        // get IPv4 and IPv6 from network interface
        Inet4Address ip4 = null;
        Inet6Address ip6 = null;
        for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
            InetAddress addr = ifaceAddr.getAddress();
            if (addr instanceof Inet4Address && ip4 == null) {
                ip4 = (Inet4Address) addr;
            } else if (addr instanceof Inet6Address && ip6 == null) {
                ip6 = (Inet6Address) addr;
            }
        }

        // get network ip
        InetAddress apIp = null;
        if (dhcp.gateway != 0) {
            try {
                apIp = InetAddresses.fromLittleEndianByteArray(Ints.toByteArray(dhcp.gateway));
            } catch (UnknownHostException e) {
                // No valid IP address
            }
        }

        // get network name
        String networkName = connection.getSSID();
        if (networkName != null) {
            networkName = NetworkManager.unquoteSSID(networkName);
        }

        return new WifiConnection(ip4, ip6, apIp, networkName, iface);
    }

    /**
     * Creates a {@link WifiConnection} object when in Access Point mode.
     * @param iface Network interface.
     * @param apName AP name.
     * @return A {@link WifiConnection} object.
     */
    public static WifiConnection fromApMode(NetworkInterface iface, String apName) {
        // get IPv4 and IPv6 from network interface
        Inet4Address ip4 = null;
        Inet6Address ip6 = null;
        for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
            InetAddress addr = ifaceAddr.getAddress();
            if (addr instanceof Inet4Address && ip4 == null) {
                ip4 = (Inet4Address) addr;
            } else if (addr instanceof Inet6Address && ip6 == null) {
                ip6 = (Inet6Address) addr;
            }
        }

        return new WifiConnection(ip4, ip6, null, apName, iface);
    }

    /**
     * Constructor. Hidden, cannot be instantiated.
     * @param ip4 IPv4.
     * @param ip6 IPv6.
     * @param apIp IP of currently connected network.
     * @param ssid SSID.
     * @param wifiInterface Network interface.
     */
    private WifiConnection(Inet4Address ip4, Inet6Address ip6, InetAddress apIp, String ssid,
                           NetworkInterface wifiInterface) {
        mIp4Address = Optional.fromNullable(ip4);
        mIp6Address = Optional.fromNullable(ip6);
        mApAddress = Optional.fromNullable(apIp);
        mNetworkName = Optional.fromNullable(ssid);
        mWifiInterface = wifiInterface;
    }

    /**
     * Checks whether an IPv4 exists.
     * @return true if an IPv4 exists, false otherwise.
     */
    public boolean hasIp4Address() {
        return mIp4Address.isPresent();
    }

    /**
     * Retrives the IPv4 address.
     * @return IPv4 address.
     * @see Inet4Address
     */
    public Optional<Inet4Address> getIp4Address() {
        return mIp4Address;
    }

    /**
     * Checks whether an IPv6 exists.
     * @return true if an IPv6 exists, false otherwise.
     */
    public boolean hasIp6Address() {
        return mIp6Address.isPresent();
    }

    /**
     * Retrives the IPv6 address.
     * @return IPv6 address.
     * @see Inet6Address
     */
    public Optional<Inet6Address> getIp6Address() {
        return mIp6Address;
    }

    /**
     * Checks whether an IP for the currently connected network exists.
     * @return true if an IP exists, false otherwise.
     */
    public boolean hasApAddress() {
        return mApAddress.isPresent();
    }

    /**
     * Retrives the currently connected network's address.
     * @return Network address.
     * @see InetAddress
     */
    public Optional<InetAddress> getApAddress() {
        return mApAddress;
    }

    /**
     * Checks whether the network has a name.
     * @return true if there is a network name, false otherwise.
     */
    public boolean hasNetworkName() {
        return mNetworkName.isPresent();
    }

    /**
     * Retrives the currently connected network's name.
     * @return Network name.
     */
    public Optional<String> getNetworkName() {
        return mNetworkName;
    }

    /**
     * Checks whether there is an active connection by checking the network name
     * @return true if there is a network name, false otherwise.
     */
    public boolean isConnected() {
        return mNetworkName.isPresent();
    }

    /**
     * Retrives the currently connected network interface.
     * @return Network interface.
     * @see NetworkInterface
     */
    public NetworkInterface getWifiInterface() {
        return mWifiInterface;
    }
}
