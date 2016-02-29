package ul.fcul.lasige.find.data;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import ul.fcul.lasige.find.beaconing.BeaconingManager;
import ul.fcul.lasige.find.data.FullContract.*;
import ul.fcul.lasige.find.lib.data.Packet;
import ul.fcul.lasige.find.packetcomm.PacketRegistry;

import java.util.Locale;

/**
 * Content provider of FIND platform. Extends {@link ContentProvider}.
 *
 * Created by hugonicolau on 05/11/2015.
 */
public class FindProvider extends ContentProvider {
    private static final String TAG = FindProvider.class.getSimpleName();

    // database controller
    private DbController mDbController;
    // database helps
    private static DbHelper sDbHelper;

    /**
     * All URI paths which can be handled by the content provider:
     * <p>- {@link UriMatch#NO_MATCH},</p>
     * <p>- {@link UriMatch#IDENTITY_LIST}, {@link UriMatch#IDENTITY_ID},</p>
     * <p>- {@link UriMatch#APP_LIST}, {@link UriMatch#APP_ID},</p>
     * <p>- {@link UriMatch#PROTOCOL_LIST}, {@link UriMatch#PROTOCOL_ID},</p>
     * <p>- {@link UriMatch#IMPLEMENTATION_LIST}, {@link UriMatch#IMPLEMENTATION_ID},</p>
     * <p>- {@link UriMatch#NEIGHBOR_LIST}, {@link UriMatch#NEIGHBOR_LIST_CURRENT},
     * {@link UriMatch#NEIGHBOR_LIST_RECENT}, {@link UriMatch#NEIGHBOR_ID},</p>
     * <p>- {@link UriMatch#NEIGHBOR_PROTOCOLS_LIST}, {@link UriMatch#NEIGHBOR_PROTOCOLS_LIST_CURRENT},
     * {@link UriMatch#NEIGHBOR_PROTOCOLS_LIST_RECENT}, {@link UriMatch#NEIGHBOR_PROTOCOLS_ID},</p>
     * <p>- {@link UriMatch#PROTOCOL_NEIGHBORS}</p>
     * <p>- {@link UriMatch#PACKET_LIST}, {@link UriMatch#PACKET_LIST_INCOMING}, {@link UriMatch#PACKET_LIST_OUTGOING},
     * {@link UriMatch#PACKET_ID}</p>.
     *
     */
    private enum UriMatch {
        NO_MATCH,

        IDENTITY_LIST(Identities.URI_ALL, Identities.CONTENT_DIR_TYPE),
        IDENTITY_ID(Identities.URI_ALL, "/#", Identities.CONTENT_ITEM_TYPE),

        APP_LIST(Apps.URI_ALL, Apps.CONTENT_DIR_TYPE),
        APP_ID(Apps.URI_ALL, "/#", Apps.CONTENT_ITEM_TYPE),

        PROTOCOL_LIST(Protocols.URI_ALL, Protocols.CONTENT_DIR_TYPE),
        PROTOCOL_ID(Protocols.URI_ALL, "/#", Protocols.CONTENT_ITEM_TYPE),

        IMPLEMENTATION_LIST(ClientImplementations.URI_ALL, ClientImplementations.CONTENT_DIR_TYPE),
        IMPLEMENTATION_ID(ClientImplementations.URI_ALL, "/#", ClientImplementations.CONTENT_ITEM_TYPE),

        NEIGHBOR_LIST(Neighbors.URI_ALL, Neighbors.CONTENT_DIR_TYPE),
        NEIGHBOR_LIST_CURRENT(Neighbors.URI_CURRENT, Neighbors.CONTENT_DIR_TYPE),
        NEIGHBOR_LIST_RECENT(Neighbors.URI_RECENT, Neighbors.CONTENT_DIR_TYPE),
        NEIGHBOR_ID(Neighbors.URI_ALL, "/#", Neighbors.CONTENT_ITEM_TYPE),

        NEIGHBOR_PROTOCOLS_LIST(NeighborProtocols.URI_ALL, NeighborProtocols.CONTENT_DIR_TYPE),
        NEIGHBOR_PROTOCOLS_LIST_CURRENT(NeighborProtocols.URI_CURRENT, NeighborProtocols.CONTENT_DIR_TYPE),
        NEIGHBOR_PROTOCOLS_LIST_RECENT(NeighborProtocols.URI_RECENT, NeighborProtocols.CONTENT_DIR_TYPE),
        NEIGHBOR_PROTOCOLS_ID(NeighborProtocols.URI_ALL, "/#", NeighborProtocols.CONTENT_ITEM_TYPE),

        PROTOCOL_NEIGHBORS(ProtocolNeighbors.URI_ITEM, "/*", ProtocolNeighbors.CONTENT_ITEM_TYPE),

        PACKET_LIST(Packets.URI_ALL, Packets.CONTENT_DIR_TYPE),
        PACKET_LIST_INCOMING(Packets.URI_INCOMING, Packets.CONTENT_DIR_TYPE),
        PACKET_LIST_OUTGOING(Packets.URI_OUTGOING, Packets.CONTENT_DIR_TYPE),
        PACKET_ID(Packets.URI_ALL, "/#", Packets.CONTENT_ITEM_TYPE);

        private final Uri mUri;
        private final String mMatchedPath;
        private final String mContentType;

        UriMatch() {
            mUri = null;
            mMatchedPath = null;
            mContentType = null;
        }

        UriMatch(Uri uri, String contentType) {
            mUri = uri;
            mMatchedPath = TextUtils.join("/", uri.getPathSegments());
            mContentType = contentType;
        }

        UriMatch(Uri uri, String suffix, String contentType) {
            mUri = uri;
            mMatchedPath = TextUtils.join("/", uri.getPathSegments()) + suffix;
            mContentType = contentType;
        }

        /**
         * Returns {@link Uri}.
         * @return An {@link Uri} object.
         */
        public Uri getUri() {
            return mUri;
        }

        /**
         * Returns Uri path joined by '/' characters.
         * @return Uri path.
         */
        public String getMatchedPath() {
            return mMatchedPath;
        }

        /**
         * Return the content type.
         * @return The content type.
         */
        public String getContentType() {
            return mContentType;
        }
    }

    /**
     * URI Matcher
     */
    public static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        for (UriMatch match : UriMatch.values()) {
            sUriMatcher.addURI(FullContract.AUTHORITY, match.getMatchedPath(), match.ordinal());
        }
    }

    @Override
    public boolean onCreate() {
        // create database controller
        mDbController = new DbController(getContext());
        // get database helper
        sDbHelper = DbHelper.getInstance(getContext());
        return true;
    }

    /**
     * Returns the {@link UriMatch} from existing Uri paths that can be handled by FIND's content provided.
     * @param uri Required Uri
     * @return {@link UriMatch} for a given Uri. If no match is found, it returns {@link UriMatch#NO_MATCH NO_MATCH}.
     * @see Uri
     * @see UriMatch
     */
    private UriMatch getSafeUriMatch(Uri uri) {
        int match_ = sUriMatcher.match(uri);
        int match = Math.max(0, match_);
        return UriMatch.values()[match];
    }

    /**
     * Retrieves {@link ClientImplementation} from an {@link Uri}.
     * @param uri Uri
     * @return A {@link ClientImplementation} object if uri has a protocol token, null if uri has no token,
     * and {@link SecurityException} if the token is not valid.
     */
    private ClientImplementation resolveImplementationDetails(Uri uri) {
        String accessToken = uri.getQueryParameter(FullContract.ACCESS_TOKEN_PARAMETER_NAME);
        if (accessToken == null) {
            Log.v(TAG, "No access token in URI");
            return null;
        }

        ClientImplementation implementation = mDbController.getImplementation(accessToken);
        if (implementation == null) {
            throw new SecurityException(
                    "Invalid access token in request for URI " + uri);
        }
        return implementation;
    }

    /**
     * Returns content type.
     * @param uri Uri
     * @return Content type of uri.
     * @see Uri
     */
    @Override
    public String getType(Uri uri) {
        UriMatch match = getSafeUriMatch(uri);
        return match.getContentType();
    }

    /**
     * Query the given uri, returning a cursor over the result set.
     * @param uri The uri for the content to retrieve.
     * @param projection A string array of which columns to return. Passing null will return the default
     *                   projection for the given uri.
     * @param selection A filter declaring which rows to return, formatted as an SQL WHERE clause
     *                  (excluding the WHERE itself). Passing null will return all rows for the given uri.
     * @param selectionArgs You may include ?s in the selection parameter, which will be replaced by the values
     *                      selectionArgs, in the order that they appear in the selection. The values will
     *                      be bound as Strings.
     * @param sortOrder How to order the rows, formatted as an SQL ORDER BY (excluding the ORDER BY itself).
     *                  Passing null will use the default sort order for the given uri. For some uri's the
     *                  sort order can't be changed.
     * @return A data cursor object, which is positioned before the first entry, or null.
     *
     * @see FullContract
     * @see ul.fcul.lasige.find.lib.data.FindContract
     * @see Cursor
     */
    @SuppressLint("NewApi")
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        UriMatch match = getSafeUriMatch(uri);
        if (match.equals(UriMatch.NO_MATCH)) {
            Log.v(TAG, "No match for URI: " + uri);
            return null;
        }

        SQLiteDatabase db = sDbHelper.getReadableDatabase();
        String table;
        String where = null;
        SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();

        switch (match) {
            case APP_LIST: {
                table = FullContract.Apps.TABLE_NAME;

                if (projection == null) {
                    projection = FullContract.Apps.PROJECTION_DEFAULT;
                }

                if (sortOrder == null) {
                    sortOrder = FullContract.Apps.SORT_ORDER_DEFAULT;
                }
                break;
            }

            case PROTOCOL_LIST: {
                table = FullContract.Protocols.TABLE_NAME;

                if (projection == null) {
                    projection = FullContract.Protocols.PROJECTION_DEFAULT;
                }

                if (sortOrder == null) {
                    sortOrder = FullContract.Protocols.SORT_ORDER_DEFAULT;
                }
                break;
            }

            case NEIGHBOR_ID:
            case NEIGHBOR_LIST:
            case NEIGHBOR_LIST_CURRENT:
            case NEIGHBOR_LIST_RECENT: {
                table = Neighbors.TABLE_NAME;
                projection = Neighbors.PROJECTION_DEFAULT;

                if (match == UriMatch.NEIGHBOR_ID) {
                    // The query is for a single neighbor, so filter only this one.
                    where = String.format(Neighbors.WHERE_CLAUSE_ITEM,
                            uri.getEncodedFragment());
                } else {
                    // The query is for a list of neighbors, so we apply the default sort order.
                    sortOrder = Neighbors.SORT_ORDER_DEFAULT;

                    // Also we may need to filter the list.
                    if (match == UriMatch.NEIGHBOR_LIST_CURRENT) {
                        where = String.format(Locale.US, Neighbors.WHERE_CLAUSE_TIME_SEEN,
                                BeaconingManager.getCurrentTimestamp());
                    } else if (match == UriMatch.NEIGHBOR_LIST_RECENT) {
                        where = String.format(Locale.US, Neighbors.WHERE_CLAUSE_TIME_SEEN,
                                BeaconingManager.getRecentTimestamp());
                    }
                }
                break;
            }

            case NEIGHBOR_PROTOCOLS_ID:
            case NEIGHBOR_PROTOCOLS_LIST:
            case NEIGHBOR_PROTOCOLS_LIST_CURRENT:
            case NEIGHBOR_PROTOCOLS_LIST_RECENT: {
                table = NeighborProtocols.VIEW_NAME;
                projection = NeighborProtocols.PROJECTION_DEFAULT;

                if (match == UriMatch.NEIGHBOR_PROTOCOLS_ID) {
                    // The query is for a single neighbor, so filter only this one.
                    where = String.format(NeighborProtocols.WHERE_CLAUSE_NEIGHBOR_ID,
                            uri.getEncodedFragment());
                } else {
                    // The query is for a list of neighbors.
                    sortOrder = Neighbors.SORT_ORDER_DEFAULT;

                    if (match == UriMatch.NEIGHBOR_PROTOCOLS_LIST_CURRENT) {
                        where = String.format(Locale.US, NeighborProtocols.WHERE_CLAUSE_TIME_SEEN,
                                BeaconingManager.getCurrentTimestamp());
                    } else if (match == UriMatch.NEIGHBOR_PROTOCOLS_LIST_RECENT) {
                        where = String.format(Locale.US, NeighborProtocols.WHERE_CLAUSE_TIME_SEEN,
                                BeaconingManager.getRecentTimestamp());
                    }
                }
                break;
            }

            case PROTOCOL_NEIGHBORS: {
                table = FullContract.ProtocolNeighbors.VIEW_NAME;
                projection = FullContract.ProtocolNeighbors.PROJECTION_DEFAULT;
                sortOrder = FullContract.ProtocolNeighbors.SORT_ORDER_DEFAULT;

                final String protocolHashAsHex = uri.getLastPathSegment();
                if (TextUtils.isEmpty(protocolHashAsHex)) {
                    Log.v(TAG, "Received request for PROTOCOL_NEIGHBORS with empty protocol hash");
                    return null;
                }

                final String filter = uri.getQueryParameter(
                        FullContract.ProtocolNeighbors.QUERY_PARAM_FILTER_TIME);

                long filterTime;
                switch (filter) {
                    case "current": {
                        filterTime = BeaconingManager.getCurrentTimestamp();
                        break;
                    }

                    case "all":
                    default: {
                        filterTime = 0;
                    }
                }

                where = String.format(
                        FullContract.ProtocolNeighbors.WHERE_CLAUSE_PROTOCOL_AND_TIME_SEEN,
                        protocolHashAsHex, filterTime);
                break;
            }

            case PACKET_LIST_INCOMING: {
                ClientImplementation implementation = resolveImplementationDetails(uri);

                table = FullContract.Packets.VIEW_NAME_INCOMING;
                projection = FullContract.Packets.PROJECTION_DEFAULT_INCOMING;
                where = String.format(FullContract.Packets.WHERE_CLAUSE_PROTOCOL,
                        implementation.getProtocolHashAsHex());

                if (match != UriMatch.PACKET_ID) {
                    sortOrder = FullContract.Packets.SORT_ORDER_DEFAULT_INCOMING;
                }
                break;
            }

            case PACKET_LIST_OUTGOING: {
                ClientImplementation implementation = resolveImplementationDetails(uri);
                table = Packets.VIEW_NAME_OUTGOING;
                projection = Packets.PROJECTION_DEFAULT;
                where = String.format(Packets.WHERE_CLAUSE_PROTOCOL, implementation.getProtocolHashAsHex());
                sortOrder = Packets.SORT_ORDER_DEFAULT_OUTGOING;
                break;
            }

            case PACKET_LIST: {
                table = FullContract.Packets.VIEW_NAME_ALL;
                projection = FullContract.Packets.PROJECTION_DEFAULT;
                sortOrder = FullContract.Packets.SORT_ORDER_DEFAULT;
                break;
            }


            default: {
                // Something which should not be queried remotely.
                return null;
            }
        }

        queryBuilder.setTables(table);
        if (where != null) {
            // TODO: revert to some form of escaped query (-> SQL injections!)
            queryBuilder.appendWhere(where);
        }

        final Cursor result = queryBuilder.query(db, projection, selection, selectionArgs, null, null, sortOrder);
        result.setNotificationUri(getContext().getContentResolver(), uri);
        return result;
    }

    /**
     * Inserts a row into a table at the given uri. If the FIND content provider supports transactions
     * the insertion will be atomic, otherwise it will return null.
     * @param uri The uri of the table to insert into.
     * @param values The initial values for the newly inserted row.
     * @return The uri of the newly created row, or null if the uri is not supported.
     * @see Uri
     * @see FullContract
     * @see ul.fcul.lasige.find.lib.data.FindContract
     */
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        UriMatch match = getSafeUriMatch(uri);
        if (match.equals(UriMatch.NO_MATCH)) {
            return null;
        }
        ClientImplementation implementation = resolveImplementationDetails(uri);

        long recordId = 0;
        Uri baseUri = match.getUri();
        switch (match) {
            case PACKET_LIST_OUTGOING: {
                final PacketRegistry registry = PacketRegistry.getInstance(getContext());
                recordId = registry.registerOutgoingPacket(implementation, values);
                break;
            }

            default:
                // won't handle this INSERT request
                return null;
        }

        if (baseUri == null || recordId <= 0) {
            throw new SQLException(
                    "Problem while inserting into URI " + uri);
        }
        return ContentUris.withAppendedId(baseUri, recordId);
    }

    /**
     * Update. As of the moment, no updates are allowed by the FIND provider.
     * @param uri The uri to modify.
     * @param values The new field values.
     * @param selection A filter to apply to rows before updating, formatted as an SQL WHERE (excluding
     *                  the WHERE itself).
     * @param selectionArgs You may include ?s in the selection parameter, which will be replaced by the values
     *                      selectionArgs, in the order that they appear in the selection. The values will
     *                      be bound as Strings.
     * @return The number of rows updated.
     */
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // No updates allowed
        return 0;
    }

    /**
     * Delete. As of the moment, no deletes are allowed by the FIND provider.
     * @param uri The uri to delete from.
     * @param selection A filter to apply to rows before updating, formatted as an SQL WHERE (excluding
     *                  the WHERE itself).
     * @param selectionArgs You may include ?s in the selection parameter, which will be replaced by the values
     *                      selectionArgs, in the order that they appear in the selection. The values will
     *                      be bound as Strings.
     * @return The number of rows deleted.
     */
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // No deletions allowed
        return 0;
    }

    /**
     * Retrieves the FIND's platform content provider.
     * @param context Application context.
     * @return {@link FindProvider} object.
     */
    public static FindProvider getLocalContentProvider(Context context) {
        final ContentProviderClient client =
                context.getContentResolver().acquireContentProviderClient(FullContract.AUTHORITY);

        return (FindProvider) client.getLocalContentProvider();
    }
}
