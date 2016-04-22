package ul.fcul.lasige.find.data;

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import com.google.protobuf.ByteString;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ul.fcul.lasige.find.apps.ProtocolRegistry;
import ul.fcul.lasige.find.apps.TokenGenerator;
import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.lib.data.Packet;
import ul.fcul.lasige.find.packetcomm.TransportPacketFactory;
import ul.fcul.lasige.find.crypto.CryptoHelper;
import ul.fcul.lasige.find.data.FullContract.ClientImplementations;
import ul.fcul.lasige.find.data.FullContract.Packets;
import ul.fcul.lasige.find.data.FullContract.PacketQueues;
import ul.fcul.lasige.find.data.FullContract.Neighbors;
import ul.fcul.lasige.find.data.FullContract.NeighborProtocols;
import ul.fcul.lasige.find.data.FullContract.ProtocolNeighbors;
import ul.fcul.lasige.find.data.FullContract.RemoteProtocols;
import ul.fcul.lasige.find.lib.data.FindContract;
import ul.fcul.lasige.find.protocolbuffer.FindProtos.TransportPacket;
import ul.fcul.lasige.find.utils.ByteUtils;

/**
 * Database controller. It provides a set of operations that can be executed on the FIND database.
 *
 * Created by hugonicolau on 04/11/2015.
 */
public class DbController {
    private static final String TAG = DbController.class.getSimpleName();

    // context
    private final Context mContext;
    // database helper
    private final DbHelper mDbHelper;

    /**
     * Constructor.
     * @param context Application context.
     */
    public DbController(Context context) {
        // set context
        mContext = context;
        // initializes database
        mDbHelper = DbHelper.getInstance(context);
    }

    /*
     * Applications
     */

    /**
     * Retrieves client application database id from a given token.
     * @param appToken Client app token.
     * @return Application id.
     */
    private long getApplicationIdByToken(String appToken) {
        // get read access to db
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();
        // query id
        final Cursor appCursor = db.query(
                FullContract.Apps.TABLE_NAME,
                new String[]{
                        FullContract.Apps._ID
                },
                FullContract.Apps.COLUMN_APP_TOKEN + " = ?",
                new String[]{
                        appToken
                },
                null, null, null, "1");

        try {
            if (!appCursor.moveToFirst()) {
                // does not exist
                return -1;
            }
            // exists!
            return appCursor.getLong(0);
        } finally {
            appCursor.close();
        }
    }

    /**
     * Returns a data cursor with all client applications.
     * @return A data cursor with client applications.
     */
    public Cursor getApplications() {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        return db.query(
                FullContract.Apps.TABLE_NAME,
                FullContract.Apps.PROJECTION_DEFAULT,
                null, null, null, null,
                FullContract.Apps.SORT_ORDER_DEFAULT);
    }

    /**
     * Insert an client application in the DB and return its token. Content resolvers are notified
     * of an change.
     * @param packageName Client app's package name
     * @return App token.
     * @see android.content.ContentResolver
     */
    public String insertApplication(String packageName) {
        Log.d(TAG, "Going insert FIND app: " + packageName);
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        // generate app token
        final String appToken = TokenGenerator.generateToken(12);

        Log.d(TAG, "FIND app token: " + appToken);
        final ContentValues values = new ContentValues();
        values.put(FullContract.Apps.COLUMN_PACKAGE_NAME, packageName);
        values.put(FullContract.Apps.COLUMN_APP_TOKEN, appToken);

        // insert
        db.insertOrThrow(FullContract.Apps.TABLE_NAME, null, values);

        // notify resolvers
        mContext.getContentResolver().notifyChange(FullContract.Apps.URI_ALL, null);
        return appToken;
    }

    /**
     * Delete an application from the DB and notifies content resolvers
     * @param appToken Client application's token.
     * @return Sum of deleted applications and protocols.
     * @see android.content.ContentResolver
     */
    public int deleteApplication(String appToken) {
        // get write access to DB
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        // start transaction
        db.beginTransaction();

        try {
            int deletedProtocolRows = 0;
            // get all client implementations for this app token
            final Cursor implementations = getImplementations(
                    FullContract.Apps.COLUMN_APP_TOKEN + " = ?",
                    new String[] { appToken });

            if (implementations.moveToFirst()) {
                // we have implementations
                // get app id
                final long appId = implementations.getLong(implementations.getColumnIndex(ClientImplementations.COLUMN_APP_ID));

                // get all protocols for this app
                final int protocolColumnId = implementations.getColumnIndex(ClientImplementations.COLUMN_PROTOCOL_ID);
                final HashSet<Long> possiblyAffectedProtocols = new HashSet<>();

                while (!implementations.isAfterLast()) {
                    possiblyAffectedProtocols.add(implementations.getLong(protocolColumnId));
                    implementations.moveToNext();
                }

                // get protocols not affected by this app
                final String sqlInClause =
                        ClientImplementations.COLUMN_PROTOCOL_ID
                                + " in ("
                                + TextUtils.join(", ", possiblyAffectedProtocols.toArray())
                                + ")"
                                + " and " + ClientImplementations.COLUMN_APP_ID + " != "
                                + appId;

                Cursor protocols = db.query(
                        ClientImplementations.TABLE_NAME,
                        new String[] { ClientImplementations.COLUMN_PROTOCOL_ID },
                        sqlInClause, null,
                        ClientImplementations.COLUMN_PROTOCOL_ID, null, null);

                final HashSet<Long> notAffectedProtocols = new HashSet<>();
                while (protocols.moveToNext()) {
                    notAffectedProtocols.add(protocols.getLong(0));
                }
                protocols.close();

                // remove not affected protocols for possibly affected - this means that we only
                // remove protocols that are not used by other apps
                possiblyAffectedProtocols.removeAll(notAffectedProtocols);
                if (!possiblyAffectedProtocols.isEmpty()) {
                    // delete protocols
                    deletedProtocolRows = db.delete(
                            FullContract.Protocols.TABLE_NAME,
                            FullContract.Protocols._ID + " in ("
                                    + TextUtils.join(", ", possiblyAffectedProtocols) + ")",
                            null);
                    // notify content resolvers of protocols change
                    mContext.getContentResolver().notifyChange(FullContract.Protocols.URI_ALL, null);
                }
                // notify content resolvers of clientimplementation changes
                mContext.getContentResolver().notifyChange(ClientImplementations.URI_ALL, null);
            }
            implementations.close();

            // delete app
            final int deletedAppRows = db.delete(FullContract.Apps.TABLE_NAME,
                    FullContract.Apps.COLUMN_APP_TOKEN + " = ?",
                    new String[] { appToken });
            // notify content resolvers of app changes
            mContext.getContentResolver().notifyChange(FullContract.Apps.URI_ALL, null);
            // notify content resolvers of packet changes
            mContext.getContentResolver().notifyChange(FullContract.Packets.URI_ALL, null);

            db.setTransactionSuccessful();
            return deletedAppRows + deletedProtocolRows;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Client Implementations
     */
    /**
     * Retrieves {@link ClientImplementation} from a given client application's token.
     * @param accessToken Client app's token.
     * @return {@link ClientImplementation} object.
     */
    public ClientImplementation getImplementation(String accessToken) {
        return getImplementation(ClientImplementations.COLUMN_TOKEN, accessToken);
    }

    /**
     * Client Implementations
     */
    /**
     * Retrieves {@link ClientImplementation} from a given client application's token.
     * @return {@link ClientImplementation} object.
     */
    public ClientImplementation getImplementation(long protocol, long appId) {
            ClientImplementation implementation = null;

            // get read access
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            // get data cursor from table
            final Cursor cursor = db.query(
                    ClientImplementations.VIEW_NAME_FULL_DETAILS,
                    ClientImplementations.PROJECTION_DEFAULT,
                    ClientImplementations.COLUMN_PROTOCOL_ID + " =? and " +ClientImplementations.COLUMN_APP_ID + " = ?" ,
                    new String[] {
                            protocol+"", appId+""
                    },
                    null, null, null, "1");

            if (cursor.moveToFirst()) {
                // we have a client implementation! create from cursor
                implementation = ClientImplementation.fromCursor(cursor);
            }

            cursor.close();
            return implementation;
        }

    /**
     * Retrieves {@link ClientImplementation} from a given client application's DB id.
     * @param id Database ID.
     * @return A {@link ClientImplementation} object.
     */
    public ClientImplementation getImplementation(long id) {
        return getImplementation(ClientImplementations._ID, String.valueOf(id));
    }

    /**
     * Returns a {@link ClientImplementation} from a given column name and filter argument.
     * @param whereColumn Column name.
     * @param whereArg Argument value.
     * @return A {@link ClientImplementation} object, or null if the row does not exists.
     */
    private ClientImplementation getImplementation(String whereColumn, String whereArg) {
        ClientImplementation implementation = null;

        // get read access
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();
        // get data cursor from table
        final Cursor cursor = db.query(
                ClientImplementations.VIEW_NAME_FULL_DETAILS,
                ClientImplementations.PROJECTION_DEFAULT,
                whereColumn + " = ?",
                new String[] {
                        whereArg
                },
                null, null, null, "1");

        if (cursor.moveToFirst()) {
            // we have a client implementation! create from cursor
            implementation = ClientImplementation.fromCursor(cursor);
        }

        cursor.close();
        return implementation;
    }

    /**
     * Get all {@link ClientImplementation}s that satisfy a given filter parameter as a data cursor.
     * @param whereColumn Table's column.
     * @param whereArgs Filter value.
     * @return Data cursor.
     */
    public Cursor getImplementations(String whereColumn, String[] whereArgs) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        return db.query(
                ClientImplementations.VIEW_NAME_FULL_DETAILS,
                ClientImplementations.PROJECTION_DEFAULT,
                null, null, null, null,
                ClientImplementations.SORT_ORDER_DEFAULT);
    }

    /**
     * Insert client implementation (for internal use).
     * @param appId DB id of client application.
     * @param protocolId DB id of protocol.
     * @param identityId DB id of platform's identity.
     * @return The row id of the newly inserted row.
     */
    private long insertRawImplementation(long appId, long protocolId, long identityId) {
        // get write access
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        // build values structure
        final ContentValues values = new ContentValues();
        values.put(ClientImplementations.COLUMN_APP_ID, appId);
        values.put(ClientImplementations.COLUMN_PROTOCOL_ID, protocolId);
        values.put(ClientImplementations.COLUMN_IDENTITY_ID, identityId);
        // generate client implementation / protocol token
        values.put(ClientImplementations.COLUMN_TOKEN, TokenGenerator.generateToken(16));

        // TODO: CONFLICT_IGNORE does not necessarily return the previous value
        // see https://code.google.com/p/android/issues/detail?id=13045
        return db.insertWithOnConflict(
                ClientImplementations.TABLE_NAME, null, values,
                SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * Insert client implementation.
     * @param appToken Client app's token.
     * @param values Values to be inserted
     * @return A {@link ClientImplementation} object or null if it was not possible to insert it.
     */
    public ClientImplementation insertImplementation(String appToken, Bundle values) {
        // get write access
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        // start transaction
        db.beginTransaction();

        try {
            // get client app id
            final long appId = getApplicationIdByToken(appToken);
            Log.d(TAG, "Error while inserting implementation!"+ appId);

            if (appId > 0) {
                // it exists! get protocol id
                long protocolId = getProtocolIdByName(values.getString(FullContract.Protocols.COLUMN_IDENTIFIER));

                if (protocolId < 0) {
                    Log.e(TAG, "inserting new < 0 ");

                    // protocol does not previously exist, lets insert it
                    protocolId = insertProtocol(
                            values.getString(FullContract.Protocols.COLUMN_IDENTIFIER),
                            values.getBoolean(FullContract.Protocols.COLUMN_ENCRYPTED),
                            values.getBoolean(FullContract.Protocols.COLUMN_SIGNED),
                            values.getString(FullContract.Protocols.COLUMN_ENDPOINT),
                            values.getString(FullContract.Protocols.COLUMN_DOWNLOAD_ENDPOINT),
                            values.getInt(FullContract.Protocols.COLUMN_DEFAULT_TTL));
                    Log.d(TAG, "client implementation id:" +protocolId);
                }

                if (protocolId > 0) {
                    Log.e(TAG, "inserting new protocol id:" + protocolId + " appID:" + appId );

                    // either insert was successful, or it previously existed
                    // insert client implementation
                    final long implementationId = insertRawImplementation(appId, protocolId, 1);
                    Log.e(TAG, "inserting new  implementationId:" + implementationId);

                    if (implementationId > 0) {
                        // success!
                        db.setTransactionSuccessful();

                        // notify content resolvers: protocols and client implementations
                        mContext.getContentResolver().notifyChange(FullContract.Protocols.URI_ALL, null);
                        mContext.getContentResolver().notifyChange(ClientImplementations.URI_ALL, null);

                        return getImplementation(implementationId);
                    }else{
                        mContext.getContentResolver().notifyChange(FullContract.Protocols.URI_ALL, null);
                        mContext.getContentResolver().notifyChange(ClientImplementations.URI_ALL, null);
                        return  getImplementation(protocolId, appId);
                    }
                }
            }
        } finally {
            db.endTransaction();
        }
        Log.e(TAG, "Error while inserting implementation!");
        return null;
    }

    /**
     * Protocols
     */
    /**
     * Returns protocol's DB id given its name.
     * @param protocolName Protocol name.
     * @return Protocol's DB id. -1 if it does not exists.
     */
    private long getProtocolIdByName(String protocolName) {
        // get read access
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        // protocol hash
        final String protocolHashAsString = CryptoHelper.createHexDigest(protocolName.getBytes());
        // get protocol cursor
        final Cursor protocolCursor = db.query(
                FullContract.Protocols.TABLE_NAME,
                new String[]{
                        FullContract.Protocols._ID
                },
                String.format(FullContract.Protocols.WHERE_CLAUSE_PROTOCOL, protocolHashAsString),
                null, null, null, null, "1");

        try {
            if (!protocolCursor.moveToFirst()) {
                // does not exist
                return -1;
            }
            return protocolCursor.getLong(0);
        } finally {
            protocolCursor.close();
        }
    }

    /**
     * Protocols
     */
    /**
     * Returns protocol's DB id given its name.
     * @param hashcode Protocol name.
     * @return Protocol's DB id. -1 if it does not exists.
     */
    public Cursor getProtocolEndpointByHash(byte [] hashcode) {
        // get read access
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        // protocol hash
        final Cursor allProtocolCursor = db.query(
                FullContract.Protocols.TABLE_NAME,
                null,null,
                null, null, null, null, null);

        while (allProtocolCursor.moveToNext()) {
           byte []hash = allProtocolCursor.getBlob(2);
            if(Arrays.equals(hash,hashcode)){
                return allProtocolCursor;
            }

        }
       return null;
    }

    /**
     * Retrieves a data cursor with all protocols.
     * @return A data cursor.
     * @see Cursor
     */
    public Cursor getProtocols() {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        return db.query(
                FullContract.Protocols.TABLE_NAME,
                null,
                FullContract.Protocols.COLUMN_DOWNLOAD_ENDPOINT  +" is not null" , null, null, null,
                null);
    }



    /**
     * Insert protocol.
     * @param name Protocol name.
     * @param encrypted Is encrypted?
     * @param authenticated Is authenticated?
     * @param defaultTtl Time to live.
     * @return The row id of the newly inserted row, or -1 if an error occurred.
     */
    private long insertProtocol(String name, Boolean encrypted, Boolean authenticated, String endpoint,String downloadEndpoint, Integer defaultTtl) {
        // build values structure
        final ContentValues values = new ContentValues();
        // name
        values.put(FullContract.Protocols.COLUMN_IDENTIFIER, name);
        // hash
        values.put(FullContract.Protocols.COLUMN_IDENTIFIER_HASH, CryptoHelper.createDigest(name.getBytes()));

        // encrypted?
        if (encrypted != null) {
            values.put(FullContract.Protocols.COLUMN_ENCRYPTED, encrypted);
        }
        // authenticated?
        if (authenticated != null) {
            values.put(FullContract.Protocols.COLUMN_SIGNED, authenticated);
        }
        // endpoint
        if (endpoint != null) {
            values.put(FullContract.Protocols.COLUMN_ENDPOINT, endpoint);
        }
        // download endpoint
        if (downloadEndpoint != null) {
            values.put(FullContract.Protocols.COLUMN_DOWNLOAD_ENDPOINT, downloadEndpoint);
        }
        // ttl
        if (defaultTtl != null) {
            values.put(FullContract.Protocols.COLUMN_DEFAULT_TTL, defaultTtl);
        }

        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        try {
            return db.insert(FullContract.Protocols.TABLE_NAME, null, values);
        } finally {
            // notify content resolvers
            mContext.getContentResolver().notifyChange(FullContract.Protocols.URI_ALL, null);
        }
    }

    /**
     * Identity
     */
    /**
     * Retrieves the platform's (single) master identity.
     * @return An {@link Identity}.
     */
    public Identity getMasterIdentity() {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();
        final Cursor identityCursor = db.query(
                FullContract.Identities.TABLE_NAME,
                FullContract.Identities.PROJECTION_DEFAULT,
                FullContract.Identities._ID + " = 1",
                null, null, null, null, "1");

        try {
            if (!identityCursor.moveToFirst()) {
                throw new IllegalStateException("No master identity found!");
            }
            return Identity.fromCursor(identityCursor);
        } finally {
            identityCursor.close();
        }
    }



    /**
     * Insert the platform's identify in the DB.
     * @param keyLabel Label.
     * @param publicKey Public key.
     * @param displayName Display name. If null or emprty, the hexadecimal value of the public key will be used.
     * @return The row id of the newly created row, or -1 if an error occurred.
     */
    public long insertIdentity(String keyLabel, byte[] publicKey, String displayName) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        // build values struture
        final ContentValues values = new ContentValues();
        values.put(FullContract.Identities.COLUMN_IDENTIFIER, keyLabel);
        values.put(FullContract.Identities.COLUMN_PUBLICKEY, publicKey);

        String name = displayName;
        if (displayName == null || displayName.isEmpty()) {
            name = ByteUtils.bytesToHex(publicKey);
        }
        values.put(FullContract.Identities.COLUMN_DISPLAY_NAME, name);

        try {
            return db.insert(FullContract.Identities.TABLE_NAME, null, values);
        } finally {
            // notify content resolvers
            mContext.getContentResolver().notifyChange(FullContract.Identities.URI_ALL, null);
        }
    }

    /**
     * Packets
     */
    /**
     * Retrieves a {@link ul.fcul.lasige.find.protocolbuffer.FindProtos.TransportPacket.Builder TransportPacket.Builder}
     * given a packet id. Throws an {@link IllegalArgumentException} if packet does not exists in the DB.
     * @param packetId Packet id.
     * @return A {@link ul.fcul.lasige.find.protocolbuffer.FindProtos.TransportPacket.Builder TransportPacket.Builder}
     * object
     * @see ul.fcul.lasige.find.protocolbuffer.FindProtos.TransportPacket.Builder
     */
    public TransportPacket.Builder getPacket(long packetId) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        // query
        final Cursor packetCursor = db.query(
                Packets.VIEW_NAME_ALL,
                Packets.PROJECTION_DEFAULT,
                Packets._ID + " = ?",
                new String[]{
                        String.valueOf(packetId)
                },
                null, null, null, "1");

        if (!packetCursor.moveToFirst()) {
            // does not exist
            throw new IllegalArgumentException("No packet with ID " + packetId);
        }

        try {
            return TransportPacketFactory.fromCursor(packetCursor);
        } finally {
            packetCursor.close();
        }
    }

    /**
     * Returns a {@link Packet} given the packet id.
     * Throws an {@link IllegalArgumentException} if packet does not exists in the DB.
     * @param packetId Packet id.
     * @return A {@link Packet} object.
     * @see Packet
     */
    public Packet getPacketView(long packetId) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        final Cursor packetCursor = db.query(
                Packets.VIEW_NAME_ALL,
                Packets.PROJECTION_DEFAULT,
                Packets._ID + " = ?",
                new String[]{
                        String.valueOf(packetId)
                },
                null, null, null, "1");

        if (!packetCursor.moveToFirst()) {
            throw new IllegalArgumentException("No packet with ID " + packetId);
        }

        try {
            return Packet.fromCursor(packetCursor);
        } finally {
            packetCursor.close();
        }
    }

    /**
     * Retrieves a data cursor with all outgoing packets.
     * @return A data cursor.
     * @see Cursor
     */
    public Cursor getOutgoingPackets() {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        return db.query(
                Packets.VIEW_NAME_OUTGOING,
                Packets.PROJECTION_DEFAULT,
                null, null, null, null,
                Packets.SORT_ORDER_DEFAULT);
    }

    /**
     * Retrieves a data cursor with all outgoing packets that were inserted since a given timestamp.
     * @param sinceTimestamp Timestamp.
     * @return A data cursor.
     * @see Cursor
     */
    public Cursor getOutgoingPackets(long sinceTimestamp) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        return db.query(
                Packets.VIEW_NAME_OUTGOING,
                Packets.PROJECTION_DEFAULT,
                String.format(Packets.WHERE_CLAUSE_TIME_RECEIVED, sinceTimestamp),
                null, null, null,
                Packets.SORT_ORDER_DEFAULT);
    }

    /**
     * Retrieves a data cursor with all  packets that were inserted since a given timestamp.
     * @param sinceTimestamp Timestamp.
     * @return A data cursor.
     * @see Cursor
     */
    public Cursor getAllPackets(long sinceTimestamp) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        return db.query(
                Packets.TABLE_NAME,
                null,
                String.format(Packets.WHERE_CLAUSE_TIME_RECEIVED, sinceTimestamp),
                null, null, null,null);
    }

    /**
     * Retrieves a data cursor with all outgoing packets that were inserted between two timestamps.
     * @param sinceTimestamp Start timestamp (inclusive).
     * @param untilTimestamp End timestamp (exclusive).
     * @return A data cursor.
     * @see Cursor
     */
    public Cursor getOutgoingPackets(long sinceTimestamp, long untilTimestamp) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        return db.query(
                Packets.VIEW_NAME_OUTGOING,
                Packets.PROJECTION_DEFAULT,
                String.format(Packets.WHERE_CLAUSE_TIME_RECEIVED_UNTIL, sinceTimestamp, untilTimestamp),
                null, null, null,
                Packets.SORT_ORDER_DEFAULT);
    }

    /**
     * Insert incoming packet in DB.
     * @param packet Packet.
     * @param queues Queues where to insert packet.
     * @return The id of the newly created row, or 0 if an error occurred.
     * @see TransportPacket
     * @see PacketQueues
     */
    public long insertIncomingPacket(TransportPacket packet, PacketQueues[] queues) {
        // build values structure
        final ContentValues data = TransportPacketFactory.toContentValues(packet);

        // get protocol registry
        final ProtocolRegistry protocolRegistry = ProtocolRegistry.getInstance(mContext);
        Log.d(TAG, "this is the proctocol when inserting:" +packet.getProtocol());
        // get client implements that use the protocol
        final Set<ClientImplementation> implementations =
                protocolRegistry.getProtocolImplementations(packet.getProtocol().toByteArray());

        if (!implementations.isEmpty()) {
            // There is an app for this protocol
            final ClientImplementation impl = implementations.iterator().next();

            // If the packet contains a MAC, then it has already been checked before. Here we
            // additionally make sure that there really was a MAC if the protocol requires it.
            if (impl.isSigned() && !packet.hasMac()) {
                Log.w(TAG, "Rejecting packet: Protocol " + impl.getProtocolName() + " requires signed data.");
                return -1;
            }

            // Decrypt the data if the packet is targeted at us (otherwise it would just be an outgoing/forwarding packet)
            if (impl.isEncrypted()) {
                Log.v(TAG, "Decrypting incoming packet... length: " + packet.getSerializedSize());
                // get origin public key
                final byte[] senderPublicKey = packet.getSourceNode().toByteArray();
                final byte[] plaintext;
                try {
                    // decrypt data
                    plaintext = CryptoHelper.decrypt(
                            mContext, packet.getData().toByteArray(), senderPublicKey);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Rejecting packet: " + e.getLocalizedMessage());
                    return -1;
                }

                // add decrypted data to values structure
                data.put(Packets.COLUMN_DATA, plaintext);
            }
        }

        // insert packet
        final long rowId = insertPacket(data, queues);
        if (rowId > 0) {
            // Notify listeners that a packet arrived
            for (ClientImplementation impl : implementations) {
                final Uri notifyUri = FindContract.buildProtocolUri(Packets.URI_INCOMING, impl.getToken());
                mContext.getContentResolver().notifyChange(notifyUri, null);
                Log.v(TAG, "Notified URI " + notifyUri);
            }
        }
        return rowId;
    }

    /**
     * Insert an outgoing packet into the DB. Data needs to be encrypted beforehand.
     * @param implementation {@link ClientImplementation}.
     * @param data Data
     * @return The id of the newly created row, or 0 if an error occurred.
     * @see ClientImplementation
     * @see ContentValues
     */
    public long insertOutgoingPacket(ClientImplementation implementation, ContentValues data) {
        // check if packet has data
        if (!data.containsKey(Packets.COLUMN_DATA)) {
            throw new IllegalArgumentException("Packet must contain data");
        }

        // set time received in minutes
        data.put(Packets.COLUMN_TIME_RECEIVED, System.currentTimeMillis() / 1000);
        // set protocol hash
        data.put(Packets.COLUMN_PROTOCOL, implementation.getProtocolHash());

        // set TTL
        if (!data.containsKey(Packets.COLUMN_TTL)) {
            final long currentTime = System.currentTimeMillis() / 1000;
            data.put(Packets.COLUMN_TTL, currentTime + implementation.getDefaultTtl());
        }

        if (implementation.isEncrypted() || implementation.isSigned()) {
            // If packets are signed or encrypted, the source node field (= node's public key) is
            // mandatory. Additionally, the source node field must be set prior to signing the whole
            // packet.
            data.put(Packets.COLUMN_SOURCE_NODE, implementation.getIdentity());
        }

        // create packet
        final TransportPacket packet = TransportPacketFactory.unsignedFromContentValues(data);

        // if is authenticated, then signed it
        if (implementation.isSigned()) {
            final byte[] signature = CryptoHelper.sign(mContext, packet.toByteArray());
            data.put(Packets.COLUMN_MAC, signature);
        }

        // set encrypted
        data.put(Packets.COLUMN_ENCRYPTED, implementation.isEncrypted());
        // set packet hash
        data.put(Packets.COLUMN_PACKET_HASH, CryptoHelper.createDigest(packet.toByteArray()));

        return insertPacket(data, new PacketQueues[] { PacketQueues.OUTGOING });
    }

    /**
     * Insert an outgoing packet into the DB. Data needs to be encrypted beforehand.
     * @param data Data
     * @return The id of the newly created row, or 0 if an error occurred.
     * @see ClientImplementation
     * @see ContentValues
     */
    public long insertDownloadedPacket(byte []  data,byte [] protocolHash, long ttl) {

        ContentValues cv = new ContentValues();
        cv.put(Packets.COLUMN_DATA, data);
        // set time received in minutes
        cv.put(Packets.COLUMN_TIME_RECEIVED, System.currentTimeMillis() / 1000);
        // set protocol hash
        cv.put(Packets.COLUMN_PROTOCOL, protocolHash);
        // set TTL
        final long currentTime = System.currentTimeMillis() / 1000;
        cv.put(Packets.COLUMN_TTL, ttl+currentTime);

        // get protocol registry
        final ProtocolRegistry protocolRegistry = ProtocolRegistry.getInstance(mContext);
        final Set<ClientImplementation> implementations =
                protocolRegistry.getProtocolImplementations(protocolHash);

        // Notify listeners that a packet arrived
        for (ClientImplementation impl : implementations) {
            final Uri notifyUri = FindContract.buildProtocolUri(Packets.URI_INCOMING, impl.getToken());
            mContext.getContentResolver().notifyChange(notifyUri, null);
            Log.v(TAG, "Notified URI " + notifyUri);
        }
        Log.d(TAG, "implementations: " + implementations.size());
        // create packet
        final TransportPacket packet = TransportPacketFactory.unsignedFromContentValues(cv);

        // set packet hash
        cv.put(Packets.COLUMN_PACKET_HASH, CryptoHelper.createDigest(packet.toByteArray()));
        /*final Uri notifyUri = FindContract.buildProtocolUri(Packets.URI_INCOMING, impl.getToken());
        mContext.getContentResolver().notifyChange(notifyUri, null);*/
        return insertPacket(cv, new PacketQueues[] { PacketQueues.OUTGOING, PacketQueues.INCOMING });
    }

    /**
     * Insert packet in given packet queues (used internally).
     * @param packet Packet.
     * @param queues Queues.
     * @return The id of the newly created row, or 0 if an error occurred.
     * @see ContentValues
     * @see PacketQueues
     */
    private long insertPacket(ContentValues packet, PacketQueues[] queues) {
        // get write access
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();

        try {
            long rowId = 0;
            try {
                // insert packet
                rowId = db.insertOrThrow(Packets.TABLE_NAME, null, packet);
            } catch (SQLiteConstraintException e) {
                // Packet already exists in database, skip adding it again
            }

            if (rowId > 0) {
                // Packet inserted successfully, now add it to the appropriate queues
                for (final PacketQueues queue : queues) {
                    final ContentValues values = new ContentValues();
                    values.put(PacketQueues.COLUMN_QUEUE, queue.ordinal());
                    values.put(PacketQueues.COLUMN_PACKET_ID, rowId);
                    db.insert(PacketQueues.TABLE_NAME, null, values);
                }

                db.setTransactionSuccessful();

                // notify content resolvers
                mContext.getContentResolver().notifyChange(Packets.URI_ALL, null);
                mContext.getContentResolver().notifyChange(Packets.URI_OUTGOING, null);
                return rowId;
            }
        } finally {
            db.endTransaction();
        }

        // error
        return 0;
    }

    /**
     * Delete packets where TTL is lower than a given timestamp  that were already synchronized
     * @param expirationTimestamp Timestamp
     * @return Number of deleted packets.
     */
    public int deleteExpiredPackets(long expirationTimestamp) {
        // TODO: clean up queues for android < 4.0 (e.g. select first, then batch-delete)
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        int expired=0;
        try {
            Log.d(TAG, "Deleting expired:" + expirationTimestamp);
            // packets in queues are also deleted; queues are just views associated with Packets table
            expired = db.delete(Packets.TABLE_NAME,
                   Packets.COLUMN_TTL + "<="+ expirationTimestamp +" AND " +  Packets.WHERE_CLAUSE_SYNCHRONIZED, null
                    );
        } finally {
            // notify content resolvers
            mContext.getContentResolver().notifyChange(Packets.URI_ALL, null);
            return expired;
        }
    }

    /**
     * Delete packets where TTL is lower than a given timestamp from the outgoing view
     * @param expirationTimestamp Timestamp
     * @return Number of deleted packets.
     */
    public void updateOutgoingView(long expirationTimestamp) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        int expired=0;
        try {

            //get all expired packets and remove them from the package ongoing queue
           Cursor cursor = db.query(Packets.VIEW_NAME_OUTGOING,
                    new String []{Packets._ID}, Packets.COLUMN_TTL  +"<=" + expirationTimestamp , null, null, null, null);
            cursor.moveToFirst();
            while (!cursor.isAfterLast()) {
                int idExpired = cursor.getInt(0);
                cursor.moveToNext();
                db.delete(PacketQueues.TABLE_NAME,PacketQueues.COLUMN_PACKET_ID +" = "+ idExpired,null);
            }
            cursor.close();

            //update the outgoing view
            db.execSQL(FullContract.Packets.SQL_DROP_VIEW_OUTGOING);
            db.execSQL(FullContract.Packets.SQL_CREATE_VIEW_OUTGOING);
        } finally {
            // notify content resolvers
            mContext.getContentResolver().notifyChange(Packets.URI_OUTGOING, null);
        }
    }

    /**
     * Delete packets where TTL is lower than a given timestamp from the outgoing view
     * @param expirationTimestamp Timestamp
     * @return Number of deleted packets.
     */
    public void updateStaleView(long expirationTimestamp) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        try {
            //update the outgoing view
            db.execSQL(FullContract.Packets.SQL_DROP_VIEW_STALE);
            db.execSQL(Packets.SQL_CREATE_VIEW_STALE_PACKETS);
        } finally {
            // notify content resolvers
            mContext.getContentResolver().notifyChange(Packets.URI_OUTGOING, null);
        }
    }


    /*
     * Neighbors
     */

    /**
     * Returns {@link Neighbor} object given its DB id. Throws an {@link IllegalArgumentException} when
     * neighbor id does not exist.
     * @param neighborId Database id.
     * @return A {@link Neighbor} object.
     */
    public Neighbor getNeighbor(long neighborId) {
        // get read access
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        // query db with id
        final Cursor neighborCursor = db.query(
                NeighborProtocols.VIEW_NAME,
                NeighborProtocols.PROJECTION_DEFAULT,
                Neighbors._ID + " = ?",
                new String[]{
                        String.valueOf(neighborId)
                },
                null, null, null, "1");

        if (!neighborCursor.moveToFirst()) {
            // no neighbor found
            throw new IllegalArgumentException("No neighbor with ID " + neighborId);
        }

        // it exists!
        try {
            return Neighbor.fromCursor(neighborCursor);
        } finally {
            neighborCursor.close();
        }
    }

    /**
     * Returns a data cursor for all neighbors seen after a given timestamp (inclusive).
     * @param timeLastSeen Timestamp.
     * @return Data cursor.
     * @see Cursor
     */
    public Cursor getNeighborsCursor(long timeLastSeen) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        return db.query(
                NeighborProtocols.VIEW_NAME,
                NeighborProtocols.PROJECTION_DEFAULT,
                String.format(NeighborProtocols.WHERE_CLAUSE_TIME_SEEN, timeLastSeen),
                null, null, null, null);
    }

    /**
     * Returns a set of {@link Neighbor} objects seen after a given timestamp (inclusive).
     * @param timeLastSeen Timestamp.
     * @return Set of {@link Neighbor} objects.
     */
    public Set<Neighbor> getNeighbors(long timeLastSeen) {
        // get data cursor with all neighbors
        Cursor neighborsCursor = getNeighborsCursor(timeLastSeen);

        // create neighbor objects
        final Set<Neighbor> neighbors = new HashSet<>();
        while (neighborsCursor.moveToNext()) {
            neighbors.add(Neighbor.fromCursor(neighborsCursor));
        }

        // close cursor
        neighborsCursor.close();

        return neighbors;
    }

    /**
     * Insert neighbor with a list of supported protocols into the DB. Throws an {@link IllegalArgumentException} if
     * values structure does not contain a {@link Neighbors#COLUMN_IDENTIFIER COLUMN_IDENTIFIER}.
     * <p>Notifies the following content resolvers' uri's: {@link Neighbors#URI_ALL}, {@link NeighborProtocols#URI_ALL},
     * {@link NeighborProtocols#URI_CURRENT}, {@link ProtocolNeighbors#URI_ITEM}.</p>
     * @param values Values data structure.
     * @param protocols List of protocols' hash values.
     * @see FullContract
     */
    public void insertNeighbor(ContentValues values, List<ByteString> protocols) {
        // get write access
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();

        // get identifier
        final byte[] neighborId = values.getAsByteArray(Neighbors.COLUMN_IDENTIFIER);
        if (neighborId == null) {
            throw new IllegalArgumentException("Can not insert node with no node id!");
        }

        try {
            // insert neighbor
            final long rawNeighborId = upsertRawNeighbor(neighborId, values);

            if (rawNeighborId > 0) {
                // success!
                if (!protocols.isEmpty()) {
                    // insert neighbor's protocols
                    insertRemoteProtocols(rawNeighborId, protocols);
                }

                // success!
                db.setTransactionSuccessful();

                // notify content resolvers
                mContext.getContentResolver().notifyChange(Neighbors.URI_ALL, null);
                mContext.getContentResolver().notifyChange(NeighborProtocols.URI_ALL, null);
                mContext.getContentResolver().notifyChange(NeighborProtocols.URI_CURRENT, null);
                mContext.getContentResolver().notifyChange(ProtocolNeighbors.URI_ITEM, null);
            }
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Resets all timestamps for last time we sent a packet to neighbors.
     * @return true if successful, false oterwise.
     */
    public boolean resetNeighborsTimeLastPacket() {
        // get write access
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();

        try {
            boolean success;

            // reset value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                // for newer Android versions
                success = resetRawNeighborTimeLastPacket_postSDK11();
            } else {
                // for older Android versions
                success = resetRawNeighborTimeLastPacket_preSDK11();
            }
            return success;
        }
        finally {
            db.endTransaction();
        }
    }

    /**
     * Resets all timestamps for last time we sent a packet to neighbors.
     * <p>This method is to be used when Android SDK is higher or equal than 11.</p>
     * @return true if number of updated rows is higher than 0, false otherwise.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private boolean resetRawNeighborTimeLastPacket_postSDK11() {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        final SQLiteStatement updateStmt = db.compileStatement(
                "update " + Neighbors.TABLE_NAME + " set "
                        + Neighbors.COLUMN_TIME_LASTPACKET+ " = ?");

        // bind updated values
        updateStmt.bindLong(1, 0);

        return (updateStmt.executeUpdateDelete() > 0);
    }

    /**
     * Resets all timestamps for last time we sent a packet to neighbors.
     * <p>This method is to be used when Android SDK is lower than 11.</p>
     * @return true.
     */
    private boolean resetRawNeighborTimeLastPacket_preSDK11() {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        String updateQueryString = "update " + Neighbors.TABLE_NAME + " set " +
                Neighbors.COLUMN_TIME_LASTPACKET + " = " + 0;

        db.execSQL(updateQueryString);
        return true;
    }

    /**
     * Update timestamp of last packet sent to a neighbor.
     * @param neighborId Neighbor's DB id.
     * @param timeLastPacket New timestamp
     * @return true if neighbor was updated, false otherwise.
     */
    public boolean updateNeighborLastPacket(byte[] neighborId, long timeLastPacket) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();

        try {
            // get neighbor row
            final SQLiteStatement selectStmt = db.compileStatement(
                    "select " + Neighbors._ID
                            + " from " + Neighbors.TABLE_NAME
                            + " where " + Neighbors.COLUMN_IDENTIFIER + " = ?");
            selectStmt.bindBlob(1, neighborId);

            long neighborRowId;
            try {
                // execute select statement
                neighborRowId = selectStmt.simpleQueryForLong();
            } catch (SQLiteDoneException e) {
                neighborRowId = -1;
            }

            boolean success = false;
            if (neighborRowId > 0) {
                // neighbor exists
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    // update for newer versions of Android
                    success = updateRawNeighbor_postSDK11(neighborRowId, timeLastPacket);
                } else {
                    // update for older versions of Android
                    success = updateRawNeighbor_preSDK11(neighborRowId, timeLastPacket);
                }
            }

            if(success)
                db.setTransactionSuccessful();

            return success;

        } finally {
            db.endTransaction();
        }
    }

    /**
     * Insert neighbor with given {@link Neighbors#COLUMN_IDENTIFIER COLUMN_IDENTIFIER}. When neighbor already exists,
     * it is updated with the new values.
     * @param neighborId {@link Neighbors#COLUMN_IDENTIFIER COLUMN_IDENTIFIER}.
     * @param values Values data structure
     * @return Id of the newly inserted or updated row.
     * @see ContentValues
     * @see Neighbors
     */
    private long upsertRawNeighbor(byte[] neighborId, ContentValues values) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();

        try {
            // select neighbor
            final SQLiteStatement selectStmt = db.compileStatement(
                    "select " + Neighbors._ID
                            + " from " + Neighbors.TABLE_NAME
                            + " where " + Neighbors.COLUMN_IDENTIFIER + " = ?");
            selectStmt.bindBlob(1, neighborId);

            long neighborRowId;
            try {
                neighborRowId = selectStmt.simpleQueryForLong();
            } catch (SQLiteDoneException e) {
                neighborRowId = -1;
            }

            // get values to insert
            final long timeLastSeen = values.getAsLong(Neighbors.COLUMN_TIME_LASTSEEN);
            final boolean multicastCapable = values.getAsBoolean(Neighbors.COLUMN_MULTICAST_CAPABLE);
            final int multicastCapableAsInt = (multicastCapable ? 1 : 0);
            final long timeLastPacket = values.getAsLong(Neighbors.COLUMN_TIME_LASTPACKET);
            final String networkName = values.getAsString(Neighbors.COLUMN_NETWORK);

            final byte[] ip4Address = values.getAsByteArray(Neighbors.COLUMN_IP4);
            assert (ip4Address == null || ip4Address.length == 4);
            final byte[] ip6Address = values.getAsByteArray(Neighbors.COLUMN_IP6);
            assert (ip6Address == null || ip6Address.length == 16);
            final byte[] btAddress = values.getAsByteArray(Neighbors.COLUMN_BLUETOOTH);
            assert (btAddress == null || btAddress.length == 6);

            boolean success;
            if (neighborRowId <= 0) {
                // neighbor has never been seen before -> INSERT
                neighborRowId = insertRawNeighbor(
                        neighborId, timeLastSeen, multicastCapableAsInt, timeLastPacket,
                        networkName, ip4Address, ip6Address, btAddress);
                success = (neighborRowId > 0);
            } else {
                // Neighbor already registered -> UPDATE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    // newer versions of android
                    success = updateRawNeighbor_postSDK11(
                            neighborRowId, timeLastSeen, multicastCapableAsInt,
                            networkName, ip4Address, ip6Address, btAddress);
                } else {
                    // older versions of android
                    success = updateRawNeighbor_preSDK11(
                            neighborRowId, timeLastSeen, multicastCapableAsInt,
                            networkName, ip4Address, ip6Address, btAddress);
                }
            }

            if (success && neighborRowId > 0) {
                // if update/insertion was successful
                db.setTransactionSuccessful();
            }
            return neighborRowId;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Insert neighbor into DB with given values.
     * @param neighborId Neighbor's {@link Neighbors#COLUMN_IDENTIFIER}.
     * @param timeLastSeen Neighbor's {@link Neighbors#COLUMN_TIME_LASTSEEN}.
     * @param multicastCapable Neighbor's {@link Neighbors#COLUMN_MULTICAST_CAPABLE}.
     * @param timeLastPacket Neighbor's {@link Neighbors#COLUMN_TIME_LASTPACKET}.
     * @param networkName Neighbor's {@link Neighbors#COLUMN_NETWORK}.
     * @param ip4Address Neighbor's {@link Neighbors#COLUMN_IP4}.
     * @param ip6Address Neighbor's {@link Neighbors#COLUMN_IP6}.
     * @param btAddress Neighbor's {@link Neighbors#COLUMN_BLUETOOTH}.
     * @return The row id of the newly inserted row.
     * @see Neighbors
     */
    private long insertRawNeighbor(
            byte[] neighborId, long timeLastSeen, int multicastCapable, long timeLastPacket,
            String networkName, byte[] ip4Address, byte[] ip6Address, byte[] btAddress) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        // insert statement
        final SQLiteStatement insertStmt = db.compileStatement(
                "insert into " + Neighbors.TABLE_NAME + " ("
                        + Neighbors.COLUMN_IDENTIFIER + ", "
                        + Neighbors.COLUMN_TIME_LASTSEEN + ", "
                        + Neighbors.COLUMN_MULTICAST_CAPABLE + ", "
                        + Neighbors.COLUMN_TIME_LASTPACKET + ", "
                        + Neighbors.COLUMN_NETWORK + ", "
                        + Neighbors.COLUMN_IP4 + ", "
                        + Neighbors.COLUMN_IP6 + ", "
                        + Neighbors.COLUMN_BLUETOOTH
                        + ") values (?, ?, ?, ?, ?, ?, ?, ?)");

        // bind values with statement
        insertStmt.bindBlob(1, neighborId);
        insertStmt.bindLong(2, timeLastSeen);
        insertStmt.bindLong(3, multicastCapable);
        insertStmt.bindLong(4, timeLastPacket);

        if (networkName == null) {
            insertStmt.bindNull(5);
        } else {
            insertStmt.bindString(5, networkName);
        }

        if (ip4Address == null) {
            insertStmt.bindNull(6);
        } else {
            insertStmt.bindBlob(6, ip4Address);
        }

        if (ip6Address == null) {
            insertStmt.bindNull(7);
        } else {
            insertStmt.bindBlob(7, ip6Address);
        }

        if (btAddress == null) {
            insertStmt.bindNull(8);
        } else {
            insertStmt.bindBlob(8, btAddress);
        }

        // insert
        return insertStmt.executeInsert();
    }

    /**
     * Update neighbor with given id ({@link Neighbors#_ID neighborRowId}) that was last seen before a give timestamp
     * ({@link Neighbors#COLUMN_TIME_LASTSEEN} timeLastSeen).
     * <p>This method is to be used when Android SDK is higher or equal than 11.</p>
     * @param neighborRowId Neighbor's {@link Neighbors#_ID}.
     * @param timeLastSeen Neighbor's {@link Neighbors#COLUMN_TIME_LASTSEEN}.
     * @param multicastCapable Neighbor's {@link Neighbors#COLUMN_MULTICAST_CAPABLE}.
     * @param networkName Neighbor's {@link Neighbors#COLUMN_NETWORK}.
     * @param ip4Address Neighbor's {@link Neighbors#COLUMN_IP4}.
     * @param ip6Address Neighbor's {@link Neighbors#COLUMN_IP6}.
     * @param btAddress Neighbor's {@link Neighbors#COLUMN_BLUETOOTH}.
     * @return true if any rows are updated, false otherwise.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private boolean updateRawNeighbor_postSDK11(
            long neighborRowId, long timeLastSeen, int multicastCapable,
            String networkName, byte[] ip4Address, byte[] ip6Address, byte[] btAddress) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        final SQLiteStatement updateStmt = db.compileStatement(
                "update " + Neighbors.TABLE_NAME + " set "
                        + Neighbors.COLUMN_TIME_LASTSEEN + " = ?,"
                        + Neighbors.COLUMN_MULTICAST_CAPABLE + " = ?,"
                        + Neighbors.COLUMN_NETWORK + " = ?,"
                        + Neighbors.COLUMN_IP4 + " = ?,"
                        + Neighbors.COLUMN_IP6 + " = ?,"
                        + Neighbors.COLUMN_BLUETOOTH + " = ?"
                        + " where " + Neighbors._ID + " = ?"
                        + " and " + Neighbors.COLUMN_TIME_LASTSEEN + " < ?");

        // Bind updated values
        updateStmt.bindLong(1, timeLastSeen);
        updateStmt.bindLong(2, multicastCapable);

        if (networkName == null) {
            updateStmt.bindNull(3);
        } else {
            updateStmt.bindString(3, networkName);
        }

        if (ip4Address == null) {
            updateStmt.bindNull(4);
        } else {
            updateStmt.bindBlob(4, ip4Address);
        }

        if (ip6Address == null) {
            updateStmt.bindNull(5);
        } else {
            updateStmt.bindBlob(5, ip6Address);
        }

        if (btAddress == null) {
            updateStmt.bindNull(6);
        } else {
            updateStmt.bindBlob(6, btAddress);
        }

        // Bind values for WHERE clause
        updateStmt.bindLong(7, neighborRowId);
        updateStmt.bindLong(8, timeLastSeen);

        return (updateStmt.executeUpdateDelete() > 0);
    }

    /**
     * Update neighbor with given id ({@link Neighbors#_ID neighborRowId}) that was last seen before a give timestamp
     * ({@link Neighbors#COLUMN_TIME_LASTSEEN} timeLastSeen).
     * <p>This method is to be used when Android SDK is lower than 11.</p>
     * @param neighborRowId Neighbor's {@link Neighbors#_ID}.
     * @param timeLastSeen Neighbor's {@link Neighbors#COLUMN_TIME_LASTSEEN}.
     * @param multicastCapable Neighbor's {@link Neighbors#COLUMN_MULTICAST_CAPABLE}.
     * @param networkName Neighbor's {@link Neighbors#COLUMN_NETWORK}.
     * @param ip4Address Neighbor's {@link Neighbors#COLUMN_IP4}.
     * @param ip6Address Neighbor's {@link Neighbors#COLUMN_IP6}.
     * @param btAddress Neighbor's {@link Neighbors#COLUMN_BLUETOOTH}.
     * @return true.
     */
    private boolean updateRawNeighbor_preSDK11(
            long neighborRowId, long timeLastSeen, int multicastCapable,
            String networkName, byte[] ip4Address, byte[] ip6Address, byte[] btAddress) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        final String networkOrNull = (networkName == null) ? "null" : "'" + networkName + "'";
        final StringBuilder updateQueryString =
                new StringBuilder("update " + Neighbors.TABLE_NAME + " set ")
                        .append(Neighbors.COLUMN_TIME_LASTSEEN + " = ")
                        .append(timeLastSeen)
                        .append(", " + Neighbors.COLUMN_MULTICAST_CAPABLE + " = ")
                        .append(multicastCapable)
                        .append(", " + Neighbors.COLUMN_NETWORK + " = ")
                        .append(networkOrNull);

        // Append other NULLable columns
        updateQueryString.append(", " + Neighbors.COLUMN_IP4 + " = ");
        if (ip4Address == null) {
            updateQueryString.append("null");
        } else {
            updateQueryString.append("X'").append(ByteUtils.bytesToHex(ip4Address)).append("'");
        }

        updateQueryString.append(", " + Neighbors.COLUMN_IP6 + " = ");
        if (ip6Address == null) {
            updateQueryString.append("null");
        } else {
            updateQueryString.append("X'").append(ByteUtils.bytesToHex(ip6Address)).append("'");
        }

        updateQueryString.append(", " + Neighbors.COLUMN_BLUETOOTH + " = ");
        if (btAddress == null) {
            updateQueryString.append("null");
        } else {
            updateQueryString.append("X'").append(ByteUtils.bytesToHex(btAddress)).append("'");
        }

        // Add WHERE clause
        updateQueryString.append(" where " + Neighbors._ID + " = ").append(neighborRowId)
                .append(" and " + Neighbors.COLUMN_TIME_LASTSEEN + " < ").append(timeLastSeen);

        db.execSQL(updateQueryString.toString());
        return true;
    }

    /**
     * Update neighbor's timestamp for last packet sent.
     * <p>This method is to be used when Android SDK is equal or higher than 11.</p>
     * @param neighborRowId Neighbor's {@link Neighbors#_ID}.
     * @param timeLastPacket New timestamp value.
     * @return true if any rows are updated, false otherwise.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private boolean updateRawNeighbor_postSDK11(long neighborRowId, long timeLastPacket) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        final SQLiteStatement updateStmt = db.compileStatement(
                "update " + Neighbors.TABLE_NAME + " set "
                        + Neighbors.COLUMN_TIME_LASTPACKET + " = ?"
                        + " where " + Neighbors._ID + " = ?");

        // bind updated values
        updateStmt.bindLong(1, timeLastPacket);

        // bind values for WHERE clause
        updateStmt.bindLong(2, neighborRowId);

        return (updateStmt.executeUpdateDelete() > 0);
    }

    /**
     * Update neighbor's timestamp for last packet sent.
     * <p>This method is to be used when Android SDK is lower than 11.</p>
     * @param neighborRowId Neighbor's {@link Neighbors#_ID}.
     * @param timeLastPacket New timestamp value.
     * @return true if any rows are updated, false otherwise.
     */
    private boolean updateRawNeighbor_preSDK11(long neighborRowId, long timeLastPacket) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        String updateQueryString = "update " + Neighbors.TABLE_NAME + " set " +
                Neighbors.COLUMN_TIME_LASTPACKET + " = " +
                timeLastPacket + " where " + Neighbors._ID + " = " + neighborRowId;

        db.execSQL(updateQueryString);
        return true;
    }

    /*
     * Remote Protocols
     */

    /**
     * Insert neighbors protocol's in DB. Existing protocols for the neighbor are deleted.
     * @param neighborRowId Neighbor's DB id.
     * @param protocols List of protocols' hash values.
     */
    private void insertRemoteProtocols(long neighborRowId, List<ByteString> protocols) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();

        try {
            // remove previously stored RemoteProtocols
            db.delete(
                    RemoteProtocols.TABLE_NAME,
                    RemoteProtocols.COLUMN_NEIGHBOR_ID + " = " + neighborRowId,
                    null);

            // insert each protocol, reusing the same prepared statement for speed.
            final SQLiteStatement insertStmt = db.compileStatement(
                    "insert into " + RemoteProtocols.TABLE_NAME + " values (?, ?, ?)");
            for (final ByteString protocol : protocols) {
                if (protocol.size() != 20) {
                    Log.w(TAG, "Protocol hash must be of length 20, not " + protocol.size());
                    continue;
                }

                insertStmt.clearBindings();
                insertStmt.bindNull(1);
                insertStmt.bindLong(2, neighborRowId);
                insertStmt.bindBlob(3, protocol.toByteArray());

                final long rowId = insertStmt.executeInsert();
                if (rowId < 0) {
                    Log.w(TAG, String.format(
                            "Error while inserting RemoteProtocol %s for neighbor %d",
                            ByteUtils.bytesToHex(protocol), neighborRowId));
                }
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void updateSyncPackets(long newSyncTime) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        String updateQueryString = "update " + Packets.TABLE_NAME + " set " +
                Packets.COLUMN_SYNCHRONIZED + " = " +
                1 + " where " + Packets.COLUMN_TIME_RECEIVED + " < " + newSyncTime + " and "  +Packets.COLUMN_SYNCHRONIZED + " = "+0;

        db.execSQL(updateQueryString);
    }
}
