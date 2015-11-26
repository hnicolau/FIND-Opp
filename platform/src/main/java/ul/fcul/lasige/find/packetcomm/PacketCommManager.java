package ul.fcul.lasige.find.packetcomm;

import android.content.Context;
import android.util.Log;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
 * Created by hugonicolau on 17/11/15.
 */
public class PacketCommManager implements NeighborObserver.NeighborCallback,
        PacketRegistry.PacketAddedCallback {
    private static final String TAG = PacketCommManager.class.getSimpleName();

    private final Context mContext;
    private final BeaconingManager mBeaconingManager;
    private final PacketRegistry mPacketRegistry;
    private final ProtocolRegistry mProtocolRegistry;
    private final NeighborObserver mNeighborObserver;
    private final Identity mIdentity;

    private PacketReceiver mPacketReceiver;

    /**
     * Mapping from neighbor node IDs to Neighbor objects.
     */
    private final Map<ByteBuffer, Neighbor> mNeighborNodeIdMap = new HashMap<>();

    /**
     * Mapping from protocol hash to neighbors which understand this protocol.
     */
    private final Multimap<ByteBuffer, Neighbor> mProtocolNeighborMap = HashMultimap.create();

    public PacketCommManager(Context context, BeaconingManager beaconingManager) {
        mBeaconingManager = beaconingManager;
        mContext = context.getApplicationContext();
        mPacketRegistry = PacketRegistry.getInstance(mContext);
        mProtocolRegistry = ProtocolRegistry.getInstance(mContext);

        mIdentity = new DbController(context).getMasterIdentity();

        mNeighborObserver = new NeighborObserver(mContext, this);
    }

    public void start() {
        Log.d(TAG, "Packet Comm Start");
        mNeighborObserver.register();
        mPacketRegistry.registerCallback(this);

        // start receiver thread
        mPacketReceiver = new PacketReceiver(mPacketRegistry, mIdentity, mProtocolRegistry);
        new Thread(mPacketReceiver).start();
    }

    public void stop() {
        Log.d(TAG, "Packet Comm Stop");
        mPacketReceiver.interrupt();

        mPacketRegistry.unregisterCallback(this);
        mNeighborObserver.unregister();
    }

    public void schedulePendingPackets(Neighbor neighbor) {

        for(Long packetId : mPacketRegistry.getPacketsIdsSince(neighbor.getTimeLastPacket())) {
            if (neighbor.hasLastSeenNetwork()) {
                mBeaconingManager.setWifiConnectionLocked(true);
            }
            PacketSenderService.startSendPacket(mContext, neighbor.getRawId(), packetId);
        }
    }

    private void scheduleSendingPackets(Neighbor neighbor) {
        Log.d(TAG, "scheduleSendingPackets");
        for (Long packetId : mPacketRegistry.getInterestingPacketIds(neighbor)) {

            if (neighbor.hasLastSeenNetwork()) {
                mBeaconingManager.setWifiConnectionLocked(true);
            }
            PacketSenderService.startSendPacket(mContext, neighbor.getRawId(), packetId);
        }
    }

    private void cancelSendingPackets(Neighbor neighbor) {
        // TODO: enable packetsenderservice to cancel sending packets to specific neighbors
    }

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

    @Override
    public void onNeighborsChanged(Set<Neighbor> currentNeighbors) {
        // Already handled all changed neighbors.
        Iterator<Neighbor> it = currentNeighbors.iterator();
        while(it.hasNext()) {
            schedulePendingPackets(it.next());
        }
    }

    @Override
    public void onOutgoingPacketAdded(FindProtos.TransportPacketOrBuilder packet, long packetId) {
        Log.d(TAG, "onOutgoingPacket");
        if (packet.hasTargetNode()) {
            // Packet is targeted, is the specific node around?
            final Neighbor target = mNeighborNodeIdMap.get(packet.getTargetNode().asReadOnlyByteBuffer());
            if (target != null) {
                // it is!
                PacketSenderService.startSendPacket(mContext, target.getRawId(), packetId);
            }
        }
        else {
            // packet is not targeted at some specific node
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
