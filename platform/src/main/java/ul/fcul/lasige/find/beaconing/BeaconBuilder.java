package ul.fcul.lasige.find.beaconing;

import android.annotation.SuppressLint;

import com.google.common.base.Optional;
import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.network.NetworkManager;
import ul.fcul.lasige.find.network.WifiConnection;
import ul.fcul.lasige.find.protocolbuffer.FindProtos;

/**
 * The class provides the functionality to build beacons.
 *
 * Created by hugonicolau on 13/11/15.
 */
public class BeaconBuilder {
    @SuppressLint("TrulyRandom")
    private static final SecureRandom sRNG = new SecureRandom();

    private final BeaconingManager mBM;
    private final FindProtos.Beacon.Builder mBeaconBuilder = FindProtos.Beacon.newBuilder();

    public BeaconBuilder(BeaconingManager context) {
        mBM = context;
    }

    /**
     * Builds a beacon without neighbor information, but with "access point likelihood" value.
     *
     * @param wifiState WiFi state.
     * @param connection Connection.
     * @param protocols Protocol's hash values.
     * @param apLikelihood AP likelihood.
     * @return Beacon.
     */
    public byte[] buildBeacon(NetworkManager.WifiState wifiState, Optional<WifiConnection> connection,
                              Set<ByteBuffer> protocols, byte apLikelihood) {

        return makeBeacon(wifiState, connection, protocols, new HashSet<Neighbor>(), apLikelihood)
                .build().toByteArray();
    }

    /**
     * Builds a beacon with neighbor information, but without "access point likelihood" value.
     *
     * @param wifiState WiFi state.
     * @param connection Connection.
     * @param protocols Protocols' hash values.
     * @param neighbors Neighbors
     * @return Beacon.
     */
    public byte[] buildBeacon(NetworkManager.WifiState wifiState, Optional<WifiConnection> connection,
                              Set<ByteBuffer> protocols, Set<Neighbor> neighbors) {

        return makeBeacon(wifiState, connection, protocols, neighbors, null)
                .build().toByteArray();
    }

    /**
     * Builds a reply beacon with information about protocols, neighbors, and original beacon.
     *
     * @param wifiState WiFi state.
     * @param connection Connection.
     * @param protocols Protocols' hash values.
     * @param neighbors Neighbors.
     * @param originalBeacon Original beacon.
     * @return Reply beacon.
     */
    public byte[] buildReply(NetworkManager.WifiState wifiState, Optional<WifiConnection> connection,
                             Set<ByteBuffer> protocols, Set<Neighbor> neighbors, FindProtos.Beacon originalBeacon) {

        return makeBeacon(wifiState, connection, protocols, neighbors, null)
                .setBeaconType(FindProtos.Beacon.BeaconType.REPLY)
                .build().toByteArray();
    }

    /**
     * Builds a beacon. Used internally.
     *
     * @param wifiState WiFi state.
     * @param wifiConnection Connection.
     * @param protocols Protocols' hash values.
     * @param neighbors Neighbors.
     * @param apLikelihood AP likelihood.
     * @return Beacon.
     */
    private FindProtos.Beacon.Builder makeBeacon(NetworkManager.WifiState wifiState, Optional<WifiConnection> wifiConnection,
                                      Set<ByteBuffer> protocols, Set<Neighbor> neighbors, Byte apLikelihood) {
        final long timeCreated = System.currentTimeMillis() / 1000;

        // Build basic data
        mBeaconBuilder.clear()
                .setTimeCreated(timeCreated)
                .setBeaconId(sRNG.nextInt());

        // Build sender information
        final FindProtos.Node.Builder senderBuilder =
                mBeaconBuilder.getSenderBuilder().setNodeId(ByteString.copyFrom(mBM.mMasterIdentity.getPublicKey()));

        if (wifiConnection.isPresent()) {
            WifiConnection connection = wifiConnection.get();
            if (connection.isConnected()) {
                if (connection.hasIp4Address()) {
                    senderBuilder.setIp4Address(
                            ByteString.copyFrom(connection.getIp4Address().get().getAddress()));
                }
                if (connection.hasIp6Address()) {
                    senderBuilder.setIp6Address(
                            ByteString.copyFrom(connection.getIp6Address().get().getAddress()));
                }
            }
        }

        /*TODO final Optional<ByteString> bluetoothAddress = mBM.mNetManager.getBluetoothAddressAsBytes();
        if (bluetoothAddress.isPresent()) {
            senderBuilder.setBtAddress(bluetoothAddress.get());
        }*/

        if (!NetworkManager.deviceSupportsMulticastWhenAsleep()) {
            senderBuilder.setMulticastCapable(false);
        }

        if (apLikelihood != null) {
            // If connected to an FIND AP, send along how "eager" this node is to take over
            // the AP role from the current AP (highest value wins)
            senderBuilder.setApLikelihood(apLikelihood);
        }

        for (final ByteBuffer protocol : protocols) {
            // NOTE: There is a ByteString constructor which takes ByteBuffer's directly, but this
            // somehow does not reliably copy the underlying byte array (it usually can do it once,
            // and then never again). I could not find the cause of that, so using the ByteBuffer's
            // byte array directly is currently the best workaround.
            senderBuilder.addProtocols(ByteString.copyFrom(protocol.array()));
        }

        // Build neighbors
        String currentNetwork = "";
        if (wifiConnection.isPresent() && wifiConnection.get().isConnected()) {
            currentNetwork = wifiConnection.get().getNetworkName().get();
        }

        for (Neighbor neighbor : neighbors) {
            // The following values are directly added to the main beaconBuilder
            final FindProtos.Node.Builder neighborBuilder =
                    mBeaconBuilder.addNeighborsBuilder()
                            .setNodeId(ByteString.copyFrom(neighbor.getNodeId()))
                            .setDeltaLastseen((int) (timeCreated - neighbor.getTimeLastSeen()));

            if (!neighbor.isMulticastCapable()) {
                neighborBuilder.setMulticastCapable(false);
            }

            if (neighbor.hasAnyIpAddress()) {
                if (neighbor.hasLastSeenNetwork()
                        && !neighbor.getLastSeenNetwork().equals(currentNetwork)) {
                    neighborBuilder.setNetwork(neighbor.getLastSeenNetwork());
                }
                if (neighbor.hasIp4Address()) {
                    neighborBuilder.setIp4Address(
                            ByteString.copyFrom(neighbor.getIp4Address().getAddress()));
                }
                if (neighbor.hasIp6Address()) {
                    neighborBuilder.setIp6Address(
                            ByteString.copyFrom(neighbor.getIp6Address().getAddress()));
                }
            }

            if (neighbor.hasBluetoothAddress()) {
                neighborBuilder.setBtAddress(
                        ByteString.copyFrom(neighbor.getBluetoothAddress()));
            }
        }

        return mBeaconBuilder;
    }
}
