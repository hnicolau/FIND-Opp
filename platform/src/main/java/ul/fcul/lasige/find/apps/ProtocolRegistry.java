package ul.fcul.lasige.find.apps;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSetMultimap;

import java.nio.ByteBuffer;
import java.util.Set;

import ul.fcul.lasige.find.data.ClientImplementation;
import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.protocolbuffer.FindProtos;

/**
 * Created by hugonicolau on 05/11/2015.
 */
public class ProtocolRegistry {
    private static ProtocolRegistry sInstance;

    /**
     * Multimap from protocol hash to ProtocolImplementations of this protocol.
     */
    private final HashMultimap<ByteBuffer, ClientImplementation> mProtocolMap;

    private final DbController mDbController;

    public static synchronized ProtocolRegistry getInstance(Context context) {
        if (sInstance == null) { sInstance = new ProtocolRegistry(context); }
        return sInstance;
    }

    private ProtocolRegistry(Context context) {
        mDbController = new DbController(context);
        mProtocolMap = loadProtocolImplementations(mDbController);
    }

    private static HashMultimap<ByteBuffer, ClientImplementation> loadProtocolImplementations(
            DbController dbController) {
        final HashMultimap<ByteBuffer, ClientImplementation> multimap = HashMultimap.create();

        final Cursor implCursor = dbController.getImplementations(null, null);
        while (implCursor.moveToNext()) {
            final ClientImplementation impl = ClientImplementation.fromCursor(implCursor);
            multimap.put(ByteBuffer.wrap(impl.getProtocolHash()), impl);
        }
        implCursor.close();

        return multimap;
    }

    public boolean hasProtocolImplementations(ByteBuffer protocol) {
        return mProtocolMap.containsKey(protocol);
    }

    public Set<ClientImplementation> getProtocolImplementations(byte[] protocol) {
        return mProtocolMap.get(ByteBuffer.wrap(protocol));
    }

    public ImmutableSetMultimap<ByteBuffer, ClientImplementation> getAllProtocolImplementations() {
        return ImmutableSetMultimap.copyOf(mProtocolMap);
    }

    public String getProtocolNameFromPacket(FindProtos.TransportPacket packet) {
        final Set<ClientImplementation> implementations =
                getProtocolImplementations(packet.getProtocol().toByteArray());
        if (!implementations.isEmpty()) {
            final ClientImplementation anyImplementation = implementations.iterator().next();
            return anyImplementation.getProtocolName();
        }
        return null;
    }

    public ClientImplementation registerProtocol(String apiKey, Bundle protocolDescription) {
        ClientImplementation implementation = mDbController.insertImplementation(apiKey, protocolDescription);

        if (implementation != null) {
            mProtocolMap.put(ByteBuffer.wrap(implementation.getProtocolHash()), implementation);
        }

        return implementation;
    }
}
