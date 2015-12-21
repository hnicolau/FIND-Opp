package ul.fcul.lasige.find.packetcomm;

import android.content.ContentValues;
import android.database.Cursor;

import com.google.protobuf.ByteString;

import ul.fcul.lasige.find.crypto.CryptoHelper;
import ul.fcul.lasige.find.protocolbuffer.FindProtos.TransportPacket;
import ul.fcul.lasige.find.protocolbuffer.FindProtos.TransportPacket.Builder;
import ul.fcul.lasige.find.data.FullContract.Packets;

/**
 * This class provides utility methods to access/build a packet that needs to be send or was just received.
 *
 * Created by hugonicolau on 10/11/15.
 */
public class TransportPacketFactory {

    /**
     * Builds a packet from a data cursor.
     * @param dataCursor Data cursor.
     * @return TransportPacket builder.
     * @see {@link Builder}
     */
    public static TransportPacket.Builder fromCursor(Cursor dataCursor) {
        final Builder packetBuilder = TransportPacket.newBuilder();

        // get source node
        final byte[] sourceNode = dataCursor.getBlob(
                dataCursor.getColumnIndexOrThrow(Packets.COLUMN_SOURCE_NODE));
        if (sourceNode != null) {
            packetBuilder.setSourceNode(ByteString.copyFrom(sourceNode));
        }

        // get target node
        final byte[] targetNode = dataCursor.getBlob(dataCursor.getColumnIndexOrThrow(Packets.COLUMN_TARGET_NODE));
        if (targetNode != null) {
            packetBuilder.setTargetNode(ByteString.copyFrom(targetNode));
        }

        // "protocol", "payload" and "ttl" are always set
        packetBuilder.setProtocol(ByteString.copyFrom(dataCursor.getBlob(dataCursor.getColumnIndex(Packets.COLUMN_PROTOCOL))));
        packetBuilder.setData(ByteString.copyFrom(dataCursor.getBlob(dataCursor.getColumnIndex(Packets.COLUMN_DATA))));
        packetBuilder.setTtl(dataCursor.getLong(dataCursor.getColumnIndex(Packets.COLUMN_TTL)));

        // get mac - this is used to check if packet is signed/authenticated
        final byte[] mac = dataCursor.getBlob(dataCursor.getColumnIndexOrThrow(Packets.COLUMN_MAC));
        if (mac != null) {
            packetBuilder.setMac(ByteString.copyFrom(mac));
        }

        return packetBuilder;
    }

    /**
     * Returns a {@link TransportPacket} object given a {@link ContentValues} data structure. The data structure
     * needs to represent an unsigned/not authenticated packet.
     *
     * @param data Data object.
     * @return TransportPacket object.
     */
    public static TransportPacket unsignedFromContentValues(ContentValues data) {
        final Builder packetBuilder = TransportPacket.newBuilder();

        // get source node
        final byte[] sourceNode = data.getAsByteArray(Packets.COLUMN_SOURCE_NODE);
        if (sourceNode != null) {
            packetBuilder.setSourceNode(ByteString.copyFrom(sourceNode));
        }

        // get target node
        final byte[] targetNode = data.getAsByteArray(Packets.COLUMN_TARGET_NODE);
        if (targetNode != null) {
            packetBuilder.setTargetNode(ByteString.copyFrom(targetNode));
        }

        // "protocol", "payload" and "ttl" are always set
        packetBuilder.setProtocol(ByteString.copyFrom(data.getAsByteArray(Packets.COLUMN_PROTOCOL)));
        packetBuilder.setData(ByteString.copyFrom(data.getAsByteArray(Packets.COLUMN_DATA)));
        packetBuilder.setTtl(data.getAsLong(Packets.COLUMN_TTL));

        return packetBuilder.build();
    }

    /**
     * Returns a {@link ContentValues} data structure given a {@link TransportPacket} object.
     * @param packet Packet.
     * @return Data structure.
     */
    public static ContentValues toContentValues(TransportPacket packet) {
        final ContentValues data = new ContentValues();

        // get source node
        final ByteString sourceNode = packet.getSourceNode();
        if (!sourceNode.isEmpty()) {
            data.put(Packets.COLUMN_SOURCE_NODE, sourceNode.toByteArray());
        }

        // get target node
        final ByteString targetNode = packet.getTargetNode();
        if (!targetNode.isEmpty()) {
            data.put(Packets.COLUMN_TARGET_NODE, targetNode.toByteArray());
        }

        // get mac - this is used to identify signed packets
        final ByteString mac = packet.getMac();
        if (!mac.isEmpty()) {
            data.put(Packets.COLUMN_MAC, mac.toByteArray());
        }

        data.put(Packets.COLUMN_TTL, packet.getTtl());
        data.put(Packets.COLUMN_PROTOCOL, packet.getProtocol().toByteArray());
        data.put(Packets.COLUMN_DATA, packet.getData().toByteArray());
        data.put(Packets.COLUMN_TIME_RECEIVED, System.currentTimeMillis() / 1000);
        data.put(Packets.COLUMN_PACKET_HASH, CryptoHelper.createDigest(packet.toByteArray()));

        return data;
    }
}
