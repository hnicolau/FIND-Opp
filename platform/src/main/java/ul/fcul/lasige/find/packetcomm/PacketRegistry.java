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
 * Created by hugonicolau on 05/11/2015.
 */
public class PacketRegistry {

    public static interface PacketAddedCallback {
        public void onOutgoingPacketAdded(TransportPacketOrBuilder packet, long packetId);
    }

    private static final String TAG = PacketRegistry.class.getSimpleName();

    private static PacketRegistry sInstance;

    private final Context mContext;
    private final DbController mDbController;
    private final ProtocolRegistry mProtocolRegistry;
    private final Boolean LOCK = false;

    /**
     * Multimap from protocols to packet IDs
     */
    private final Multimap<ByteBuffer, Long> mOutgoingPacketProtocolsMap = HashMultimap.create();
    private final Set<Long> mForwardingPackets = new HashSet<>();
    private final Set<Long> mUnencryptedBroadcastingPackets = new HashSet<>();
    private final Set<PacketAddedCallback> mCallbacks = new HashSet<>();

    public static synchronized PacketRegistry getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PacketRegistry(context);
        }
        return sInstance;
    }

    private PacketRegistry(Context context) {
        mContext = context;
        mDbController = new DbController(context);
        mProtocolRegistry = ProtocolRegistry.getInstance(context);

        fillPacketCaches();
    }

    public void fillPacketCaches() {
        synchronized (LOCK) {
            final Cursor packetCursor = mDbController.getOutgoingPackets();

            final int colIdxProtocol = packetCursor.getColumnIndexOrThrow(Packets.COLUMN_PROTOCOL);
            final int colIdxPacketId = packetCursor.getColumnIndexOrThrow(Packets._ID);
            final int colIdxTarget = packetCursor.getColumnIndexOrThrow(Packets.COLUMN_TARGET_NODE);
            final int colIdxEncrypted = packetCursor.getColumnIndexOrThrow(Packets.COLUMN_ENCRYPTED);
            final int colIdxQueue = packetCursor.getColumnIndexOrThrow(PacketQueues.COLUMN_QUEUE);

            while (packetCursor.moveToNext()) {
                final long packetId = packetCursor.getLong(colIdxPacketId);
                mOutgoingPacketProtocolsMap.put(ByteBuffer.wrap(packetCursor.getBlob(colIdxProtocol)), packetId);

                if (packetCursor.getInt(colIdxQueue) == PacketQueues.FORWARDING.ordinal()) {
                    mForwardingPackets.add(packetId);
                } else if (packetCursor.isNull(colIdxTarget) && packetCursor.getInt(colIdxEncrypted) == 0) {
                    mUnencryptedBroadcastingPackets.add(packetId);
                }
            }
        }
    }

    public void registerCallback(PacketAddedCallback callback) {
        mCallbacks.add(callback);
    }

    public void unregisterCallback(PacketAddedCallback callback) {
        mCallbacks.remove(callback);
    }

    public void registerIncomingPacket(TransportPacket packet, PacketQueues... queues) {
        final byte[] senderPublicKey = packet.getSourceNode().toByteArray();

        // Check MAC if provided
        if (packet.hasMac()) {
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
                return;
            }
        }

        synchronized (LOCK) {
            // Insert packet data
            final long incomingPacketId = mDbController.insertIncomingPacket(packet, queues);
            if (incomingPacketId > 0) {
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

    public long registerOutgoingPacket(ClientImplementation implementation, ContentValues packetData) {
        synchronized (LOCK) {
            final long packetId = mDbController.insertOutgoingPacket(implementation, packetData);
            if (packetId > 0) {
                mOutgoingPacketProtocolsMap.put(ByteBuffer.wrap(implementation.getProtocolHash()), packetId);

                if (!packetData.containsKey(Packets.COLUMN_TARGET_NODE)
                        && !implementation.isEncrypted()) {
                    mUnencryptedBroadcastingPackets.add(packetId);
                }

                TransportPacketOrBuilder packet = mDbController.getPacket(packetId);
                for (PacketAddedCallback callback : mCallbacks) {
                    callback.onOutgoingPacketAdded(packet, packetId);
                }
            }
            return packetId;
        }
    }

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

    public Set<Long> getPacketsIdsSince(long timestamp) {
        Cursor cursor = mDbController.getOutgoingPackets(timestamp);
        Set<Long> ids = new HashSet<>();
        while (cursor.moveToNext()) {
            ids.add(Packet.fromCursor(cursor).getPacketId());
        }
        return ids;
    }

    public boolean isUnencryptedBroadcastPacket(long packetId) {
        synchronized (LOCK) {
            return mUnencryptedBroadcastingPackets.contains(packetId);
        }
    }
}
