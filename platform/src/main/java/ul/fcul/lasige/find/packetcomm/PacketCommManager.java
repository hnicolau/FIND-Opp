package ul.fcul.lasige.find.packetcomm;

import android.content.Context;
import android.util.Log;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import ul.fcul.lasige.find.apps.ProtocolRegistry;
import ul.fcul.lasige.find.beaconing.BeaconingManager;
import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.data.Identity;
import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.lib.data.NeighborObserver;
import ul.fcul.lasige.find.protocolbuffer.FindProtos;

/**
 * Class responsible for managing sending and receiving packets, according to requests and/or new neighbors
 * reachable.
 *
 * <p>It locks WiFi access for network operations (sending packets) to guarantee that packets will be sent.</p>
 *
 * Created by hugonicolau on 17/11/15.
 */
public class PacketCommManager implements NeighborObserver.NeighborCallback, PacketRegistry.PacketAddedCallback {
    private static final String TAG = PacketCommManager.class.getSimpleName();

    private final Context mContext;
    // beaconing manager to lock wifi
    private final BeaconingManager mBeaconingManager;
    // registry of all packets
    private final PacketRegistry mPacketRegistry;
    // registry of all protocols
    private final ProtocolRegistry mProtocolRegistry;
    // neighbors oberserver for notification of new discovered neighbors
    private final NeighborObserver mNeighborObserver;
    // public key used to receive packets
    private final Identity mIdentity;
    // runnable to receive packets
    private PacketReceiver mPacketReceiver;

    // mapping from neighbor node IDs to Neighbor objects.
    private final Map<ByteBuffer, Neighbor> mNeighborNodeIdMap = new HashMap<>();

    // mapping from protocol hash to neighbors which understand this protocol.
    private final Multimap<ByteBuffer, Neighbor> mProtocolNeighborMap = HashMultimap.create();

    /**
     * Constructor.
     * @param context Application context.
     * @param beaconingManager Beaconing manager
     * @see BeaconingManager
     */
    public PacketCommManager(Context context, BeaconingManager beaconingManager) {
        mBeaconingManager = beaconingManager;
        mContext = context.getApplicationContext();
        mPacketRegistry = PacketRegistry.getInstance(mContext);
        mProtocolRegistry = ProtocolRegistry.getInstance(mContext);
        mIdentity = new DbController(context).getMasterIdentity();
        mNeighborObserver = new NeighborObserver(mContext, this);
    }

    /**
     * Starts the package communication manager by registering changes for neighbors and new packets,
     * and starts listening for neighbors' packets.
     */
    public void start() {
        Log.d(TAG, "Packet Comm Start");
        mNeighborObserver.register();
        mPacketRegistry.registerCallback(this);

        // start receiver thread
        mPacketReceiver = new PacketReceiver(mPacketRegistry, mIdentity, mProtocolRegistry);
        new Thread(mPacketReceiver).start();
    }

    /**
     * Stops the package communication manager.
     */
    public void stop() {
        Log.d(TAG, "Packet Comm Stop");
        mPacketReceiver.interrupt();

        mPacketRegistry.unregisterCallback(this);
        mNeighborObserver.unregister();
    }

    /**
     * Sends all new interesting packets since last time a given neighbor was seen.
     * @param neighbor Neighbor object.
     */
    public void schedulePendingPackets(Neighbor neighbor) {
        // for all new packets
        for(Long packetId : mPacketRegistry.getOutgoingPacketsIdsSince(neighbor.getTimeLastPacket())) {
            if (neighbor.hasLastSeenNetwork()) {
                // neighbor is in a WiFi network
                mBeaconingManager.setWifiConnectionLocked(true);
            }
            // enqueues packet in sender service
            PacketSenderService.startSendPacket(mContext, neighbor.getRawId(), packetId);
        }
    }

    /**
     * Sends all interesting packets to a given {@link Neighbor} via WiFi. Interesting packets include all
     * forwarding packets, enqueued packets with no target neighbor (broadcasts) and packets
     * with protocols supported by the neighbor. Overlapping packets are sent only once.
     *
     * <p>This method acquires the WiFi lock to guarantee that connection is available during sending.</p>
     *
     * @param neighbor Neighbor object.
     */
    private void scheduleSendingPackets(Neighbor neighbor) {
        // for all packets
        for (Long packetId : mPacketRegistry.getInterestingPacketIds(neighbor)) {
            if (neighbor.hasLastSeenNetwork()) {
                // neighbor is in a WiFi network, lock connection
                mBeaconingManager.setWifiConnectionLocked(true);
            }
            // enqueues packet in sender service
            PacketSenderService.startSendPacket(mContext, neighbor.getRawId(), packetId);
        }
    }

    /**
     * Not supported at the moment.
     * @param neighbor Neighbor object.
     */
    private void cancelSendingPackets(Neighbor neighbor) {
        // TODO: enable packetsenderservice to cancel sending packets to specific neighbors
    }

    /**
     * Callback triggered by {@link NeighborObserver}. It sends interesting packets to newly connected
     * neighbor.
     *
     * @param currentNeighbor Neighbor object.
     * @see PacketCommManager#scheduleSendingPackets(Neighbor)
     */
    @Override
    public void onNeighborConnected(Neighbor currentNeighbor) {
        Log.d(TAG, "On Neighbor CONNECTED");
        // add neighbor to helper mappings
        mNeighborNodeIdMap.put(ByteBuffer.wrap(currentNeighbor.getNodeId()), currentNeighbor);
        for (ByteBuffer protocol : currentNeighbor.getSupportedProtocols()) {
            mProtocolNeighborMap.put(protocol, currentNeighbor);
        }

        // check if new neighbor is eligible for packets
        scheduleSendingPackets(currentNeighbor);
    }

    /**
     * Callback triggered by {@link NeighborObserver}. Cancels all sending packets.
     *
     * @param recentNeighbor Neighbor object.
     */
    @Override
    public void onNeighborDisconnected(Neighbor recentNeighbor) {
        Log.d(TAG, "On Neighbor DISCONNECTED");
        // Remove neighbor from helper mappings
        mNeighborNodeIdMap.remove(ByteBuffer.wrap(recentNeighbor.getNodeId()));
        for (ByteBuffer protocol : recentNeighbor.getSupportedProtocols()) {
            mProtocolNeighborMap.remove(protocol, recentNeighbor);
        }

        cancelSendingPackets(recentNeighbor);
    }

    /**
     * Callback triggered by {@link NeighborObserver}. Sends new packets (since last time
     * each neighbor was seen) to all neighbors.
     * @param currentNeighbors Set of current available neighbors.
     */
    @Override
    public void onNeighborsChanged(Set<Neighbor> currentNeighbors) {
        Iterator<Neighbor> it = currentNeighbors.iterator();
        while(it.hasNext()) {
            schedulePendingPackets(it.next());
        }
    }

    /**
     * Callback triggered by {@link ul.fcul.lasige.find.lib.data.PacketObserver} when a new packet is enqueued
     * in the platform.
     * @param packet Packet.
     * @param packetId Packet's id.
     */
    @Override
    public void onOutgoingPacketAdded(FindProtos.TransportPacketOrBuilder packet, long packetId) {
        Log.d(TAG, "onOutgoingPacket");
        if (packet.hasTargetNode()) {
            // packet is targeted, is the specific node around?
            final Neighbor target = mNeighborNodeIdMap.get(packet.getTargetNode().asReadOnlyByteBuffer());
            if (target != null) {
                // it is! send it
                PacketSenderService.startSendPacket(mContext, target.getRawId(), packetId);
            }
        }
        else {
            // packet is not targeted at some specific node, broadcast to all that support that protocol!
            for (Neighbor neighbor : mNeighborNodeIdMap.values()) {
                final boolean isUnencryptedPacket = mPacketRegistry.isUnencryptedBroadcastPacket(packetId);
                final boolean isSupportedByNeighbor =
                        mProtocolNeighborMap
                                .get(packet.getProtocol().asReadOnlyByteBuffer())
                                .contains(neighbor);

                if (isUnencryptedPacket || isSupportedByNeighbor) {
                    PacketSenderService.startSendPacket(mContext, neighbor.getRawId(), packetId);
                }
            }
        }
    }

}
