package ul.fcul.lasige.find.lib.data;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.BaseColumns;

import static com.google.common.collect.ObjectArrays.concat;

/**
 * Created by hugonicolau on 03/11/2015.
 *
 * This class contains all URIs, table names, SQL statement to be used by the ContentResolver, which
 * will interface with the FIND service ContentProvider
 *
 * @see ContentResolver
 * @see android.content.ContentProvider
 *
 */
public class FindContract {

    // prevents instantiation
    private FindContract() {}

    /**
     * The authority of the Find data provider.
     */
    public static final String AUTHORITY = "ul.fcul.lasige.find.dataprovider";

    /**
     * The content URI for the top-level Find data provider authority. This field is only used
     * internally in this library project. To retrieve content, use one of the full URIs provided by
     * the different content tables below.
     */
    private static final Uri URI_BASE = Uri.parse("content://" + AUTHORITY);

    /**
     * Random URI parameter required to restrict access to protocols an app has registered itself
     * for. Append this to any URI, using the protocol-specific token generated upon registration.
     */
    public static final String ACCESS_TOKEN_PARAMETER_NAME = "protocol_token";

    /**
     * Constants for the PacketQueue table of the Find data provider. This is merely a helper
     * table and can only sensibly be used in combination with a JOIN to the Packets table. This is
     * also the reason why there is neither an _ID field nor any URI defined, and the primary key is
     * formed by the packet id together with the queue field.
     */
    public static enum PacketQueues {
        INCOMING, OUTGOING, FORWARDING;

        /**
         * The name of the packet queue table in the database.
         */
        public static final String TABLE_NAME = "PacketToQueue";

        /**
         * The numeric id of the packet. This is a foreign key to the Packets table.
         * <p>
         * Type: INTEGER
         */
        public static final String COLUMN_PACKET_ID = "raw_packet_id";

        /**
         * The queue this packet belongs to. Valid values are defined by the Queues enum.
         * <p>
         * Type: TEXT
         */
        public static final String COLUMN_QUEUE = "queue";
    }

    /**
     * Constants for the Protocol table of the Find data provider. A protocol describes the format
     * in which different applications exchange data over the Find platform.
     */
    public static final class Protocols implements BaseColumns {
        /**
         * The name of the protocols table in the database.
         */
        public static final String TABLE_NAME = "Protocols";

        /**
         * The unique, human readable protocol identifier.
         * <p>
         * Type: TEXT
         */
        public static final String COLUMN_IDENTIFIER = "protocol_id";

        /**
         * The hash of the human readable protocol identifier.
         * <p>
         * Type: BLOB (20 bytes)
         */
        public static final String COLUMN_IDENTIFIER_HASH = "hash";

        /**
         * If packets should be encrypted by default (leveraging the public key of the receiver).
         * <p>
         * Type: BOOLEAN (as INTEGER)
         */
        public static final String COLUMN_ENCRYPTED = "encrypted";

        /**
         * If packets should be signed by default (leveraging the own public key). Turn this off to
         * enable incognito operation (no sender node will be set).
         * <p>
         * Type: BOOLEAN (as INTEGER)
         */
        public static final String COLUMN_SIGNED = "signed";

        /**
         * The default TTL (in seconds) packets of this protocol should have, relative to the time a
         * packet is being sent (e.g., 3600 for a default TTL of X+1h). A "null" value means
         * "no default", hence none is set for such packets.
         * <p>
         * Type: INTEGER
         */
        public static final String COLUMN_DEFAULT_TTL = "default_ttl";

        /**
         * A projection of the default columns in the protocol table.
         */
        public static final String[] PROJECTION_DEFAULT =
                {
                        _ID, COLUMN_IDENTIFIER, COLUMN_ENCRYPTED, COLUMN_SIGNED, COLUMN_DEFAULT_TTL
                };

        /**
         * The default sort order for directories of protocols.
         */
        public static final String SORT_ORDER_DEFAULT = _ID + " ASC";

        /**
         * The base content URI for this table.
         */
        public static final Uri URI_ALL = Uri.withAppendedPath(URI_BASE, "protocols");

        /**
         * The MIME type for a directory of protocols.
         */
        public static final String CONTENT_DIR_TYPE =
                ContentResolver.CURSOR_DIR_BASE_TYPE + "/vnd." + AUTHORITY + ".protocols";

        /**
         * The MIME type for a single protocol.
         */
        public static final String CONTENT_ITEM_TYPE =
                ContentResolver.CURSOR_ITEM_BASE_TYPE + "/vnd." + AUTHORITY + ".protocols";
    }


    /**
     * Constants for the Packets table of the Find data provider.
     * <p>
     * This table offers 4 different views on the data:
     * <ul>
     * <li>INCOMING: All packets which can be handled by a protocol implementer on this device,
     * except packets which are directly targeted to other nodes.
     * <li>OUTGOING: All packets created to be sent from this device.
     * <li>FORWARDING: All packets which are targeted at some other node, or no node at all.
     * Requires a special permission.
     * <li>All packets, with all metadata. Requires a special permission.
     */
    public static final class Packets implements BaseColumns {
        /**
         * The name of the packets table in the database.
         */
        public static final String TABLE_NAME = "Packets";

        /**
         * The name of the table view for incoming packets.
         */
        public static final String VIEW_NAME_INCOMING = "IncomingPackets_View";

        /**
         * The name of the table view for all outgoing packets.
         */
        public static final String VIEW_NAME_OUTGOING = "OutgoingPackets_View";

        /**
         * The name of the table view for all packets with their queues.
         */
        public static final String VIEW_NAME_ALL = "AllPackets_View";

        /**
         * The identifier of the node the packet originally came from.
         * <p>
         * Type: BLOB
         */
        public static final String COLUMN_SOURCE_NODE = "source_node";

        /**
         * The identifier of the node the packet is finally targeted at.
         * <p>
         * Type: BLOB
         */
        public static final String COLUMN_TARGET_NODE = "target_node";

        /**
         * The latest time when a packet should not be forwarded anymore, as a timestamp in UTC.
         * <p>
         * Type: INTEGER
         */
        public static final String COLUMN_TTL = "ttl";

        /**
         * The protocol this packet implements, as hash of the protocol name.
         * <p>
         * Type: BLOB (20 bytes)
         */
        public static final String COLUMN_PROTOCOL = "protocol_hash";

        /**
         * The serialized (and possibly encrypted) payload of the packet.
         * <p>
         * Type: BLOB
         */
        public static final String COLUMN_DATA = "data";

        /**
         * The message authentication code (MAC) over the packet header and payload.
         * <p>
         * Type: BLOB
         */
        public static final String COLUMN_MAC = "mac";

        /**
         * The time this packet was received by the platform, as a timestamp in UTC.
         * <p>
         * Type: INTEGER
         */
        public static final String COLUMN_TIME_RECEIVED = "time_received";

        /**
         * The hash of the packet. This field is only used on INSERTs together with a UNIQUE
         * constraint to prevent storing duplicate packets.
         * <p>
         * Type: BLOB (20 bytes)
         */
        public static final String COLUMN_PACKET_HASH = "packet_hash";

        /**
         * A projection of the default columns in the packets table.
         */
        public static final String[] PROJECTION_DEFAULT =
                {
                        _ID, COLUMN_SOURCE_NODE, COLUMN_TARGET_NODE, COLUMN_DATA, COLUMN_TTL,
                        COLUMN_PROTOCOL, COLUMN_MAC, COLUMN_TIME_RECEIVED, PacketQueues.COLUMN_QUEUE
                };

        /**
         * A projection of the default columns in the incoming view.
         */
        public static final String[] PROJECTION_DEFAULT_INCOMING =
                {
                        _ID, COLUMN_SOURCE_NODE, COLUMN_DATA, COLUMN_TIME_RECEIVED
                };

        /**
         * The default sort order for directories of all packets.
         */
        public static final String SORT_ORDER_DEFAULT =
                _ID + " DESC, " + PacketQueues.COLUMN_QUEUE + " ASC";
        /**
         * The default sort order for directories of incoming packets.
         */
        public static final String SORT_ORDER_DEFAULT_INCOMING = COLUMN_TIME_RECEIVED + " DESC";

        /**
         * The default sort order for directories of outgoing packets.
         */
        public static final String SORT_ORDER_DEFAULT_OUTGOING =
                PacketQueues.COLUMN_QUEUE + " ASC," + COLUMN_TTL + " ASC";

        /**
         * The base content URI for this table.
         */
        public static final Uri URI_ALL = Uri.withAppendedPath(URI_BASE, "packets");

        /**
         * The content URI for the directory of incoming packets.
         */
        public static final Uri URI_INCOMING = Uri.withAppendedPath(URI_ALL, "incoming");

        /**
         * The content URI for the directory of outgoing packets.
         */
        public static final Uri URI_OUTGOING = Uri.withAppendedPath(URI_ALL, "outgoing");

        /**
         * The MIME type for a directory of packets.
         */
        public static final String CONTENT_DIR_TYPE =
                ContentResolver.CURSOR_DIR_BASE_TYPE + "/vnd." + AUTHORITY + ".packets";

        /**
         * The MIME type for a single packet.
         */
        public static final String CONTENT_ITEM_TYPE =
                ContentResolver.CURSOR_ITEM_BASE_TYPE + "/vnd." + AUTHORITY + ".packets";
    }

    /**
     * Constants for the Neighbor table of the FIND data provider. Each item represents exactly
     * one neighbor the FIND platform has seen recently. Besides listing *all* neighbors ever
     * seen, this table provides two additional views with their respective URIs. The first only
     * exposes the neighbors which are currently reachable, and the second exposes these neighbors
     * which are not reachable anymore.
     */
    public static final class Neighbors implements BaseColumns {
        /**
         * The name of the neighbors table in the database.
         */
        public static final String TABLE_NAME = "Neighbors";

        /**
         * The neighbor's public identifier, an ECC public key.
         * <p>
         * Type: BLOB
         */
        public static final String COLUMN_IDENTIFIER = "neighbor_id";

        /**
         * The last time this neighbor has been seen, as timestamp in UTC.
         * <p>
         * Type: INTEGER
         */
        public static final String COLUMN_TIME_LASTSEEN = "time_lastseen";

        /**
         * The last time we successfully sent a packet to this neighbor, as timestamp in UTC.
         * <p>
         * Type: INTEGER
         */
        public static final String COLUMN_TIME_LASTPACKET = "time_lastpacket";

        /**
         * If the neighbor is capable of receiving multicast beacons.
         * <p>
         * Type: BOOLEAN (as INTEGER)
         */
        public static final String COLUMN_MULTICAST_CAPABLE = "is_multicast_capable";

        /**
         * The network name where this neighbor has been seen the last time.
         * <p>
         * Type: TEXT
         */
        public static final String COLUMN_NETWORK = "network";

        /**
         * The neighbor's IPv4 address when it was last connected, as packed bytes.
         * <p>
         * Type: BLOB (4 bytes)
         */
        public static final String COLUMN_IP4 = "ip4";

        /**
         * The neighbor's IPv6 address when it was last connected, as packed bytes.
         * <p>
         * Type: BLOB (16 bytes)
         */
        public static final String COLUMN_IP6 = "ip6";

        /**
         * The neighbor's Bluetooth MAC address, as packed bytes.
         * <p>
         * Type: BLOB (6 bytes)
         */
        public static final String COLUMN_BLUETOOTH = "bluetooth";

        /**
         * A projection of the default columns in the neighbors table.
         */
        public static final String[] PROJECTION_DEFAULT =
                {
                        _ID, COLUMN_IDENTIFIER, COLUMN_TIME_LASTSEEN, COLUMN_MULTICAST_CAPABLE,
                        COLUMN_TIME_LASTPACKET, COLUMN_NETWORK, COLUMN_IP4, COLUMN_IP6, COLUMN_BLUETOOTH
                };

        /**
         * The default sort order for directories of neighbors.
         */
        public static final String SORT_ORDER_DEFAULT = COLUMN_TIME_LASTSEEN + " DESC";

        /**
         * The base content URI for this table.
         */
        public static final Uri URI_ALL = Uri.withAppendedPath(URI_BASE, "neighbors");

        /**
         * The content URI for the directory of currently connected neighbors.
         */
        public static final Uri URI_CURRENT = Uri.withAppendedPath(URI_ALL, "current");

        /**
         * The content URI for the directory of neighbors recently connected (but not anymore).
         */
        public static final Uri URI_RECENT = Uri.withAppendedPath(URI_ALL, "recent");

        /**
         * The MIME type for a directory of neighbors.
         */
        public static final String CONTENT_DIR_TYPE =
                ContentResolver.CURSOR_DIR_BASE_TYPE + "/vnd." + AUTHORITY + ".neighbors";

        /**
         * The MIME type for a single neighbor.
         */
        public static final String CONTENT_ITEM_TYPE =
                ContentResolver.CURSOR_ITEM_BASE_TYPE + "/vnd." + AUTHORITY + ".neighbors";
    }

    public static final class RemoteProtocols implements BaseColumns {
        /**
         * The name of the remote protocols table in the database.
         */
        public static final String TABLE_NAME = "RemoteProtocols";

        /**
         * Foreign key to the ID in the neighbor table.
         * <p>
         * Type: INTEGER
         */
        public static final String COLUMN_NEIGHBOR_ID = "raw_neighbor_id";

        /**
         * Hash of the protocol supported by the neighbor.
         * <p>
         * Type: BLOB (20 bytes)
         */
        public static final String COLUMN_PROTOCOL_HASH = "protocol_hash";
    }

    public static final class NeighborProtocols {
        /**
         * The name of the neighbor protocol view in the database.
         */
        public static final String VIEW_NAME = "NeighborProtocols_View";

        /**
         * A projection of the default columns in the neighbor protocols view.
         */
        public static final String[] PROJECTION_DEFAULT = concat(
                Neighbors.PROJECTION_DEFAULT,
                RemoteProtocols.COLUMN_PROTOCOL_HASH);

        /**
         * The base content URI for this view.
         */
        public static final Uri URI_ALL = Uri.withAppendedPath(URI_BASE, "neighbor_protocols");

        /**
         * The content URI for this view, restricted to current neighbors.
         */
        public static final Uri URI_CURRENT = Uri.withAppendedPath(URI_ALL, "current");

        /**
         * The content URI for this view, restricted to recent neighbors.
         */
        public static final Uri URI_RECENT = Uri.withAppendedPath(URI_ALL, "recent");

        /**
         * The MIME type for a directory of protocol neighbors.
         */
        public static final String CONTENT_DIR_TYPE =
                ContentResolver.CURSOR_DIR_BASE_TYPE + "/vnd." + AUTHORITY + ".neighbor_protocols";

        /**
         * The MIME type for a single protocol neighbor.
         */
        public static final String CONTENT_ITEM_TYPE =
                ContentResolver.CURSOR_ITEM_BASE_TYPE + "/vnd." + AUTHORITY + ".neighbor_protocols";
    }

    public static final class ProtocolNeighbors {
        /**
         * The name of the protocol neighbor view in the database.
         */
        public static final String VIEW_NAME = "ProtocolNeighbors_View";

        /**
         * A projection of the default columns in the protocol neighbors view.
         */
        public static final String[] PROJECTION_DEFAULT = concat(
                Neighbors.PROJECTION_DEFAULT,
                RemoteProtocols.COLUMN_PROTOCOL_HASH);

        /**
         * The default sort order for directories of protocol neighbors.
         */
        public static final String SORT_ORDER_DEFAULT = Neighbors.COLUMN_TIME_LASTSEEN + " DESC";

        /**
         * The base content URI for this view.
         */
        public static final Uri URI_ITEM = Uri.withAppendedPath(URI_BASE, "protocol_neighbor");

        /**
         * The MIME type for a single protocol neighbor result set.
         */
        public static final String CONTENT_ITEM_TYPE = Neighbors.CONTENT_DIR_TYPE;

        /**
         * URI query string parameter to filter for "current", "recent" or "all" nodes (optional).
         */
        public static final String QUERY_PARAM_FILTER_TIME = "filter";
    }

    public static Uri buildProtocolUri(Uri baseUri, String protocolToken) {
        return baseUri.buildUpon()
                .appendQueryParameter(ACCESS_TOKEN_PARAMETER_NAME, protocolToken)
                .build();
    }
}
