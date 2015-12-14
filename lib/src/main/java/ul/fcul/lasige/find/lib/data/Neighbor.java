package ul.fcul.lasige.find.lib.data;

import android.database.Cursor;
import android.util.Log;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;

import ul.fcul.lasige.find.lib.data.FindContract.Neighbors;
import ul.fcul.lasige.find.lib.data.FindContract.RemoteProtocols;

/**
 * This class represents a neighbor node.
 *
 * Created by hugonicolau on 13/11/15.
 */
public class Neighbor implements Comparable<Neighbor> {
    private final static String TAG = Neighbor.class.getSimpleName();

    // bytes in node id
    public static final int BYTES_SHORT_NODE_ID = 16;

    // encoders
    private final static BaseEncoding HEX_CODER = BaseEncoding.base16();
    private final static BaseEncoding BASE64_CODER = BaseEncoding.base64Url();

    // raw node id
    private final long mRawId;
    // node id in byte array
    private final byte[] mNodeId;
    // last time we've seen this node
    private final long mTimeLastSeen;
    // last time we sent a packet to this neighbor
    private final long mTimeLastPacket;
    // is node capable of multicast
    private final boolean mMulticastCapable;
    // last wifi network we've seen the neighbor
    private final String mLastSeenNetwork;
    // ip4 address
    private final Inet4Address mIp4;
    // ip6 address
    private final Inet6Address mIp6;
    // bluetooth address
    private final byte[] mBt;
    // set of supported protocols
    private final HashSet<ByteBuffer> mSupportedProtocols;

    /**
     * Returns a Neighbor object from a data cursor. This method is useful to build new neighbor objects
     * when querying the FIND service via ContentResolver.
     * @param dataCursor Data object.
     * @return Neighbor object.
     */
    public static Neighbor fromCursor(Cursor dataCursor) {
        final int colIdxRawId = dataCursor.getColumnIndexOrThrow(Neighbors._ID);
        final long rawId = dataCursor.getLong(colIdxRawId);

        // First, all non-NULL fields
        final byte[] nodeId = dataCursor.getBlob(dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_IDENTIFIER));
        final long timeLastSeen = dataCursor.getLong(dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_TIME_LASTSEEN));
        final int multicastCapable = dataCursor.getInt(dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_MULTICAST_CAPABLE));
        final long timeLastPacket = dataCursor.getLong(dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_TIME_LASTPACKET));

        // Now all NULLable fields
        Inet4Address ip4Address = null;
        final int colIdxIp4 = dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_IP4);
        if (!dataCursor.isNull(colIdxIp4)) {
            try {
                ip4Address = (Inet4Address) InetAddress.getByAddress(dataCursor.getBlob(colIdxIp4));
            } catch (UnknownHostException e) {
                Log.w(TAG, "Encountered invalid IPv4 address stored for node " + rawId);
            }
        }

        Inet6Address ip6Address = null;
        final int colIdxIp6 = dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_IP6);
        if (!dataCursor.isNull(colIdxIp6)) {
            try {
                ip6Address = (Inet6Address) InetAddress.getByAddress(dataCursor.getBlob(colIdxIp6));
            } catch (UnknownHostException e) {
                Log.w(TAG, "Encountered invalid IPv6 address stored for node " + rawId);
            }
        }

        byte[] btAddress = null;
        final int colIdxBt = dataCursor.getColumnIndex(Neighbors.COLUMN_BLUETOOTH);
        if (!dataCursor.isNull(colIdxBt)) {
            btAddress = dataCursor.getBlob(colIdxBt);
        }

        final String networkName = dataCursor.getString(
                dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_NETWORK));

        // Last but not least, collect all protocols
        final HashSet<ByteBuffer> supportedProtocols = new HashSet<>();
        final int protocolIndex = dataCursor.getColumnIndexOrThrow(RemoteProtocols.COLUMN_PROTOCOL_HASH);

        long currentRawId = rawId;
        while (rawId == currentRawId) {
            final byte[] protocolBytes = dataCursor.getBlob(protocolIndex);
            if (protocolBytes != null) {
                supportedProtocols.add(ByteBuffer.wrap(protocolBytes));
            }

            if (dataCursor.moveToNext()) {
                currentRawId = dataCursor.getLong(colIdxRawId);
            } else {
                break;
            }
        }
        dataCursor.moveToPrevious();

        return new Neighbor(
                rawId, nodeId, timeLastSeen, timeLastPacket, multicastCapable > 0, networkName,
                ip4Address, ip6Address, btAddress, supportedProtocols);
    }

    /**
     * Constructor.
     * @param rawId Raw neighbor id.
     * @param neighborId Neighbor id.
     * @param timeLastSeen Last time the neighbor as been seen as timestamp in UTC.
     * @param timeLastPacket Last time a packet was sent to the neighbor.
     * @param multicastCapable If the neighbor is capable of receiving multicast beacons.
     * @param lastSeenNetwork The network name the neighbor was last seen.
     * @param ip4 The neighbor's IPv4 address when it was last connected.
     * @param ip6 The neighbor's IPv6 address when it was last connected.
     * @param bt The neighbor's Bluetooth address when it was last connected.
     * @param protocols A list of protocols implemented by the neighbor.
     */
    private Neighbor(long rawId, byte[] neighborId, long timeLastSeen, long timeLastPacket, boolean multicastCapable,
                     String lastSeenNetwork, Inet4Address ip4, Inet6Address ip6, byte[] bt,
                     HashSet<ByteBuffer> protocols) {
        mRawId = rawId;
        mNodeId = neighborId;
        mTimeLastSeen = timeLastSeen;
        mTimeLastPacket = timeLastPacket;
        mMulticastCapable = multicastCapable;
        mLastSeenNetwork = lastSeenNetwork;
        mIp4 = ip4;
        mIp6 = ip6;
        mBt = bt;
        mSupportedProtocols = protocols;
    }

    /**
     * Returns the neighbor's id as a long.
     * @return Raw id.
     */
    public long getRawId() {
        return mRawId;
    }

    /**
     * Returns the neighbor's id as a byte array.
     * @return Id.
     */
    public byte[] getNodeId() {
        return mNodeId;
    }

    /**
     * Returns the neighbor's id as string (hexadecimal).
     * @return Id.
     */
    public String getNodeIdAsHex() {
        return HEX_CODER.encode(mNodeId);
    }

    /**
     * Returns the short version of the neighbor's id as a string (hexadecimal).
     * @return Id.
     */
    public String getShortNodeIdAsHex() {
        return HEX_CODER.encode(mNodeId, 0, BYTES_SHORT_NODE_ID);
    }

    /**
     * Returns the neighbor's id as a string (base 64).
     * @return Id.
     */
    public String getNodeIdAsBase64() {
        return BASE64_CODER.encode(mNodeId);
    }

    /**
     * Returns the time the neighbors was last seen.
     * @return Time last seen.
     */
    public long getTimeLastSeen() {
        return mTimeLastSeen;
    }

    /**
     * Returns the time the last packet was sent to the neighbor.
     * @return Time last sent packet.
     */
    public long getTimeLastPacket() {
        return mTimeLastPacket;
    }

    /**
     * Returns whether the node is capable of receiving beacons via multicast.
     * @return Is multicast capable.
     */
    public boolean isMulticastCapable() {
        return mMulticastCapable;
    }

    /**
     * Returns the whether the neighbor was seen in a WiFi network.
     * @return Has last seen in a WiFi network.
     */
    public boolean hasLastSeenNetwork() {
        return mLastSeenNetwork != null;
    }

    /**
     * Returns the WiFi network's name where the neighbor was last seen.
     * @return Network name.
     */
    public String getLastSeenNetwork() {
        return mLastSeenNetwork;
    }

    /**
     * Returns whether the neighbor as any valid ip address (either IPv4 or IPv6).
     * @return Has any valid IP address.
     */
    public boolean hasAnyIpAddress() {
        return (mIp4 != null || mIp6 != null);
    }

    /**
     * Returns the neighbor's IP address. In case an IPv4 exists, it will be returned first.
     * @return A valid IP address (preferrably the IPv4) or null otherwise;
     */
    public InetAddress getAnyIpAddress() {
        // Preferrably the IPv4 address
        if (mIp4 != null) {
            return mIp4;
        }
        if (mIp6 != null) {
            return mIp6;
        }
        return null;
    }

    /**
     * Returns whether the neighbor a valid IPv4 address.
     * @return Has a valid IPv4 address.
     */
    public boolean hasIp4Address() {
        return mIp4 != null;
    }

    /**
     * Returns the neighbor's IPv4 address.
     * @return A IPv4 address or null if it's valid;
     */
    public Inet4Address getIp4Address() {
        return mIp4;
    }

    /**
     * Returns whether the neighbor a valid IPv6 address.
     * @return Has a valid IPv6 address.
     */
    public boolean hasIp6Address() {
        return mIp6 != null;
    }

    /**
     * Returns the neighbor's IPv6 address.
     * @return A IPv6 address or null if it isn't valid;
     */
    public Inet6Address getIp6Address() {
        return mIp6;
    }

    /**
     * Returns whether the neighbor as a Bluetooth address.
     * @return Has a Bluetooth address.
     */
    public boolean hasBluetoothAddress() {
        return mBt != null;
    }

    /**
     * Returns the neighbor's Bluetooth address.
     * @return A Bluetooth address or null if it isn't valid;
     */
    public byte[] getBluetoothAddress() {
        return mBt;
    }

    /**
     * Returns a set of neighbor's supported protocols.
     * @return Set of supported protocols.
     */
    public ImmutableSet<ByteBuffer> getSupportedProtocols() {
        return ImmutableSet.copyOf(mSupportedProtocols);
    }

    /**
     * Returns weather two neighbors are the same. It is true if they are the same object or have
     * the same node id. False otherwise.
     * @param other Object to compare to.
     * @return Whether two objects represent the same neighbor.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof Neighbor)) {
            return false;
        }
        final Neighbor o = (Neighbor) other;
        return Arrays.equals(mNodeId, o.getNodeId());
    }

    /**
     * Returns an hash code based on the node id.
     * @return Hash code.
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(mNodeId);
    }

    /**
     * Returns a String representation of the neighbor with ID and the time it was last seen.
     * @return String representation of the neighbor
     */
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", getShortNodeIdAsHex())
                .add("lastSeen", mTimeLastSeen)
                .toString();
    }

    /**
     * Compare two neighbors.
     * @param another Neighbors to compare to.
     * @return 0 if they are equal; Negative if the neighbor was seen more recently, positive otherwise;
     * In case both neighbors were seen at the same time it returns positive if the neighbors has a higher
     * id, negative otherwise.
     */
    @Override
    public int compareTo(Neighbor another) {
        final byte[] otherId = another.getNodeId();

        if (this.equals(another)) {
            return 0;
        }

        // Sort in descending order of when the node was last seen
        final long timeDifference = another.getTimeLastSeen() - mTimeLastSeen;
        if (timeDifference == 0) {
            // When last seen at the same time, sort by node ID in descending order
            for (int i = 0; i < mNodeId.length; i++) {
                if (mNodeId[i] != otherId[i]) {
                    return mNodeId[i] - otherId[i];
                }
            }
        }
        return (int) timeDifference;
    }
}
