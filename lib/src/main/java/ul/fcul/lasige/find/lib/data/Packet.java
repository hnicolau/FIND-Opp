package ul.fcul.lasige.find.lib.data;

import android.database.Cursor;

import com.google.common.io.BaseEncoding;

import ul.fcul.lasige.find.lib.data.FindContract.Packets;

/**
 * Created by hugonicolau on 03/11/2015.
 *
 * This class represents a communication packet.
 */
public class Packet {
    private final static BaseEncoding HEX_CODER = BaseEncoding.base16();

    // packet id generated by the FIND platform
    private final long mPacketId;
    // received timestamp
    private final long mTimeReceived;
    // source node
    private final byte[] mSourceNode;
    // serialized data
    private final byte[] mData;

    // constructor
    public Packet(long packetId, long timeReceived, byte[] sourceNode, byte[] data){
        mPacketId = packetId;
        mTimeReceived = timeReceived;
        mSourceNode = sourceNode;
        mData = data;
    }

    /**
     * Retrieves the packet ID.
     * @return Packet ID.
     */
    public long getPacketId() { return mPacketId; }

    /**
     * Retrieves the time the packet was received as a timestamp.
     * @return Time packet was received.
     */
    public long getTimeReceived() { return mTimeReceived; }

    /**
     * Retrieves the source node ID for the packet as a byte array.
     * @return Source node ID.
     */
    public byte[] getSourceNode() { return mSourceNode; }

    /**
     * Retrieves the source node ID for the packet as a String (hexadecimal)
     * @return Source node ID.
     */
    public String getSourceNodeAsHex() {
        if (mSourceNode != null) {
            return HEX_CODER.encode(mSourceNode);
        }
        return null;
    }

    /**
     * Retrieves the data contained by the packet as a byte array (serialized).
     * @return Packet's data.
     */
    public byte[] getData() { return mData; }

    /**
     * Return a Packet object from a data cursor. This method is useful to build new packet when
     * querying the FIND service via ContentResolver.
     *
     * @param data Data cursor.
     * @return Packet object.
     */
    public static Packet fromCursor(Cursor data) {
        final int colIdxSourceNode = data.getColumnIndex(Packets.COLUMN_SOURCE_NODE);
        byte[] sourceNode = null;
        if (!data.isNull(colIdxSourceNode)) {
            sourceNode = data.getBlob(colIdxSourceNode);
        }

        return new Packet(
                data.getLong(data.getColumnIndex(Packets._ID)),
                data.getLong(data.getColumnIndex(Packets.COLUMN_TIME_RECEIVED)),
                sourceNode,
                data.getBlob(data.getColumnIndex(Packets.COLUMN_DATA)));
    }
}
