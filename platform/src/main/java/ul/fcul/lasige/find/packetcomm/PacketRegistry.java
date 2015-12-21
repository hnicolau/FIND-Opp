package ul.fcul.lasige.find.packetcomm;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import ul.fcul.lasige.find.apps.ProtocolRegistry;
import ul.fcul.lasige.find.crypto.CryptoHelper;
import ul.fcul.lasige.find.data.ClientImplementation;
import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.data.FullContract.Packets;
import ul.fcul.lasige.find.data.FullContract.PacketQueues;
import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.lib.data.Packet;
import ul.fcul.lasige.find.protocolbuffer.FindProtos.TransportPacketOrBuilder;
import ul.fcul.lasige.find.protocolbuffer.FindProtos.TransportPacket;

/**
 * Registry and manager of existing packets within the FIND platform. It is a singleton class, thus
 * it should be access through {@link PacketRegistry#getInstance(Context)}.
 *
 * Created by hugonicolau on 05/11/2015.
 */
public class PacketRegistry {

    /**
     * Callback for outgoing packets.
     */
    public interface PacketAddedCallback {
        void onOutgoingPacketAdded(TransportPacketOrBuilder packet, long packetId);
    }

    private static final String TAG = PacketRegistry.class.getSimpleName();

    // singleton instance
    private static PacketRegistry sInstance;

    private final Context mContext;
    // database controller
    private final DbController mDbController;
    // protocol registry (used to access platform's protocols)
    private final ProtocolRegistry mProtocolRegistry;
    // used to guarantee synchronized access to variables
    private final Boolean LOCK = false;

    // multimap from protocols to packet IDs
    private final Multimap<ByteBuffer, Long> mOutgoingPacketProtocolsMap = HashMultimap.create();
    // platform's forwarding packets
    private final Set<Long> mForwardingPackets = new HashSet<>();
    // broadcast (open) packets
    private final Set<Long> mUnencryptedBroadcastingPackets = new HashSet<>();
    // callbacks that need to be notified
    private final Set<PacketAddedCallback> mCallbacks = new HashSet<>();

    /**
     * Retrieves the singleton instance of {@link PacketRegistry}.
     * @param context Application context.
     * @return Singleton instance of {@link PacketRegistry}.
     */
    public static synchronized PacketRegistry getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PacketRegistry(context);
        }
        return sInstance;
    }

    /**
     * Constructor.
     * @param context Application context.
     */
    private PacketRegistry(Context context) {
        // save context
        mContext = context;
        // create access to database object
        mDbController = new DbController(context);
        // get protocol registry
        mProtocolRegistry = ProtocolRegistry.getInstance(context);

        // initialized datastructures with database information
        fillPacketCaches();
    }

    /**
     * It initializes packets' data structures with all existing information in the database.
     */
    public void fillPacketCaches() {
        synchronized (LOCK) {
            // get all outgoing packets
            final Cursor packetCursor = mDbController.getOutgoingPackets();

            final int colIdxProtocol = packetCursor.getColumnIndexOrThrow(Packets.COLUMN_PROTOCOL);
            final int colIdxPacketId = packetCursor.getColumnIndexOrThrow(Packets._ID);
            final int colIdxTarget = packetCursor.getColumnIndexOrThrow(Packets.COLUMN_TARGET_NODE);
            final int colIdxEncrypted = packetCursor.getColumnIndexOrThrow(Packets.COLUMN_ENCRYPTED);
            final int colIdxQueue = packetCursor.getColumnIndexOrThrow(PacketQueues.COLUMN_QUEUE);

            // for all packets, add it to respective data structures
            while (packetCursor.moveToNext()) {
                // get id
                final long packetId = packetCursor.getLong(colIdxPacketId);
                // add it to map, protocol - packet id
                mOutgoingPacketProtocolsMap.put(ByteBuffer.wrap(packetCursor.getBlob(colIdxProtocol)), packetId);

                if (packetCursor.getInt(colIdxQueue) == PacketQueues.FORWARDING.ordinal()) {
                    // it is a forwarding packet
                    mForwardingPackets.add(packetId);
                } else if (packetCursor.isNull(colIdxTarget) && packetCursor.getInt(colIdxEncrypted) == 0) {
                    // no target and is not encrypted, it is a broadcast packet
                    mUnencryptedBroadcastingPackets.add(packetId);
                }
            }
        }
    }

    /**
     * Registers a callback to be notified about outgoing packets enqueued to the platform.
     * @param callback Callback.
     */
    public void registerCallback(PacketAddedCallback callback) {
        mCallbacks.add(callback);
    }

    /**
     * Unregisters callback about outgoing packets.
     * @param callback Callback
     */
    public void unregisterCallback(PacketAddedCallback callback) {
        mCallbacks.remove(callback);
    }

    /**
     * Adds packet from neighbor to platform and given queues.
     *
     * <p>If packet is signed (has a mac address), then it verifies signed data.</p>
     *
     * @param packet Packet.
     * @param queues Queues to be added to.
     */
    public void registerIncomingPacket(TransportPacket packet, PacketQueues... queues) {
        // get public key
        final byte[] senderPublicKey = packet.getSourceNode().toByteArray();

        // check MAC if provided
        if (packet.hasMac()) {
            // it is! packet is signed
            final ByteString mac = packet.getMac();
            final byte[] signedPacket = packet.toBuilder().clearMac().build().toByteArray();

            boolean success = false;
            try {
                success = CryptoHelper.verify(signedPacket, mac.toByteArray(), senderPublicKey);
            } catch (RuntimeException e) {
                Log.d(TAG, e.getLocalizedMessage());
            }

            if (!success) {
                Log.w(TAG, "Rejecting packet: Could not verify signed data.");
                // not properly signed, ignore
                return;
            }
        }

        synchronized (LOCK) {
            // insert packet data in database
            final long incomingPacketId = mDbController.insertIncomingPacket(packet, queues);
            if (incomingPacketId > 0) {
                // success
                // add it to forwarding queue, if applicable
                for (PacketQueues queue : queues) {
                    if (queue == PacketQueues.FORWARDING) {
                        mForwardingPackets.add(incomingPacketId);
                        break;
                    }
                }

                Log.v(TAG, "Received packet for protocol " + mProtocolRegistry.getProtocolNameFromPacket(packet));
            }
        }
    }

    /**
     * Registers an outgoing packet to be send to neighbors.
     * @param implementation {@link ClientImplementation} object.
     * @param packetData Packet data.
     * @return Packet id in database or 0 if an error occurred.
     */
    public long registerOutgoingPacket(ClientImplementation implementation, ContentValues packetData) {
        // synch access
        synchronized (LOCK) {
            // insert and get id
            final long packetId = mDbController.insertOutgoingPacket(implementation, packetData);
            if (packetId > 0) {
                // success!
                // add it to the map protocol - packet id
                mOutgoingPacketProtocolsMap.put(ByteBuffer.wrap(implementation.getProtocolHash()), packetId);

                if (!packetData.containsKey(Packets.COLUMN_TARGET_NODE) && !implementation.isEncrypted()) {
                    // it is a broadcast packet
                    mUnencryptedBroadcastingPackets.add(packetId);
                }

                // get packet from database
                TransportPacketOrBuilder packet = mDbController.getPacket(packetId);
                for (PacketAddedCallback callback : mCallbacks) {
                    // notify callbacks
                    callback.onOutgoingPacketAdded(packet, packetId);
                }
            }
            return packetId;
        }
    }

    /**
     * Retrieves a set of packet ids that should be sent to a given neighbor. These include all
     * forwarding packets, broadcasting packets (not encrypted or with a target), and all packets with
     * protocols supported by the neighbor. Duplicate packets are included only once.
     * @param neighbor Neighbor object.
     * @return Set of packet ids.
     */
    public Set<Long> getInterestingPacketIds(Neighbor neighbor) {
        // All FORWARDING packets are interesting
        final HashSet<Long> interestingPacketIds = new HashSet<>(mForwardingPackets);
        synchronized (LOCK) {
            // Also, all broadcasting packets
            interestingPacketIds.addAll(mUnencryptedBroadcastingPackets);

            // Additionally, all packets with protocols supported by the neighbor are interesting (may
            // be overlapping with FORWARDING packets, which is why we're using a HashSet in the first
            // place.
            for (ByteBuffer protocol : neighbor.getSupportedProtocols()) {
                interestingPacketIds.addAll(mOutgoingPacketProtocolsMap.get(protocol));
            }
        }

        Log.v(TAG, String.format(
                "Returning %d interesting packets for neighbor %s",
                interestingPacketIds.size(), neighbor.getShortNodeIdAsHex()));

        return interestingPacketIds;
    }

    /**
     * Retrieves all outgoing packets' ids existing in the platform since a given timestamp.
     * @param timestamp Timestamp.
     * @return Outgoing packets' ids.
     */
    // TODO use outgoing map instead of access DB similarly to getInterestingPacketIds
    public Set<Long> getPacketsIdsSince(long timestamp) {
        Cursor cursor = mDbController.getOutgoingPackets(timestamp);
        Set<Long> ids = new HashSet<>();
        while (cursor.moveToNext()) {
            ids.add(Packet.fromCursor(cursor).getPacketId());
        }
        return ids;
    }

    /**
     * Returns whether the packet with a given id is a broadcasting packet.
     * @param packetId Packet id.
     * @return true if it is a broadcasting packet, false otherwise.
     */
    public boolean isUnencryptedBroadcastPacket(long packetId) {
        synchronized (LOCK) {
            return mUnencryptedBroadcastingPackets.contains(packetId);
        }
    }
}
