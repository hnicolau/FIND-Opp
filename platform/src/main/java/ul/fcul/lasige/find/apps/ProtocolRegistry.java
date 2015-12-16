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
 * Registry and manager of existing protocols within the FIND platform. It is a singleton class, thus
 * it should be access through {@link ProtocolRegistry#getInstance(Context) getInstance}.
 *
 * <p>It keeps a mapping between Protocol's hash code and {@link ClientImplementation ClientImplementations}</p>
 *
 * Created by hugonicolau on 05/11/2015.
 */
public class ProtocolRegistry {
    private static ProtocolRegistry sInstance;

    // multimap from protocol hash to ProtocolImplementations of this protocol.
    private final HashMultimap<ByteBuffer, ClientImplementation> mProtocolMap;
    // database controller
    private final DbController mDbController;

    /**
     * Retrieves the singleton instance of {@link ProtocolRegistry}.
     * @param context Application context.
     * @return An object of type {@link ProtocolRegistry}.
     */
    public static synchronized ProtocolRegistry getInstance(Context context) {
        if (sInstance == null) { sInstance = new ProtocolRegistry(context); }
        return sInstance;
    }

    /**
     * Constructor.
     * @param context Application context.
     */
    private ProtocolRegistry(Context context) {
        // create db controller
        mDbController = new DbController(context);
        // load stored protocols
        mProtocolMap = loadProtocolImplementations(mDbController);
    }

    /**
     * Loads all previously stored protocols in the database.
     * @param dbController Database controller.
     * @return Map containing Protocol hash - {@link ClientImplementation ClientImplementation} for
     * all registered protocols.
     */
    private static HashMultimap<ByteBuffer, ClientImplementation> loadProtocolImplementations(
            DbController dbController) {
        // create map
        final HashMultimap<ByteBuffer, ClientImplementation> multimap = HashMultimap.create();

        // get all client implementations (protocol - app)
        final Cursor implCursor = dbController.getImplementations(null, null);
        while (implCursor.moveToNext()) {
            // create clientimplementation
            final ClientImplementation impl = ClientImplementation.fromCursor(implCursor);
            // add to map (adds, does not replace)
            multimap.put(ByteBuffer.wrap(impl.getProtocolHash()), impl);
        }
        implCursor.close();

        return multimap;
    }

    /**
     * Checks whether the protocol hash code is registered.
     * @param protocol Protocol hashcode
     * @return true if the protocol is registered, false otherwise.
     */
    public boolean hasProtocolImplementations(ByteBuffer protocol) {
        return mProtocolMap.containsKey(protocol);
    }

    /**
     * Retrieves all client implementations with a given protocol.
     * @param protocol Protocol has a byte array.
     * @return Set of {@link ClientImplementation ClientImplementations}
     */
    public Set<ClientImplementation> getProtocolImplementations(byte[] protocol) {
        return mProtocolMap.get(ByteBuffer.wrap(protocol));
    }

    /**
     * Retrieves all protocols and client implementations.
     * @return Set with protocol hash as key and corresponding list of client implementations.
     * @see ClientImplementation
     */
    public ImmutableSetMultimap<ByteBuffer, ClientImplementation> getAllProtocolImplementations() {
        return ImmutableSetMultimap.copyOf(mProtocolMap);
    }

    /**
     * Retrieves the protocol identifier (name) from a
     * {@link ul.fcul.lasige.find.protocolbuffer.FindProtos.TransportPacket TransportPacket}.
     * @param packet Packet.
     * @return Protocol name if it exists in registry, null otherwise.
     */
    public String getProtocolNameFromPacket(FindProtos.TransportPacket packet) {
        // get client implementations for protocol in packet
        final Set<ClientImplementation> implementations = getProtocolImplementations(packet.getProtocol().toByteArray());
        if (!implementations.isEmpty()) {
            // it exists! get implementation
            final ClientImplementation anyImplementation = implementations.iterator().next();
            // return name
            return anyImplementation.getProtocolName();
        }
        // protocol is not registered
        return null;
    }

    /**
     * Stores the mapping between API key and protocol description. It returns a ClientImplementation containing
     * a unique access token that will be later required publish data and subscribe to data updates.
     *
     * @param apiKey Client application's API key.
     * @param protocolDescription Client application's protocol description.
     * @return ClientImplementation, which is a representation of the mapping between client app and
     * protocol, and contains a unique token.
     * @see ClientImplementation
     */
    public ClientImplementation registerProtocol(String apiKey, Bundle protocolDescription) {
        ClientImplementation implementation = mDbController.insertImplementation(apiKey, protocolDescription);

        if (implementation != null) {
            mProtocolMap.put(ByteBuffer.wrap(implementation.getProtocolHash()), implementation);
        }

        return implementation;
    }
}
