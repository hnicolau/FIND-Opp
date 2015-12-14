package ul.fcul.lasige.find.lib.service;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ul.fcul.lasige.find.lib.data.FindContract;
import ul.fcul.lasige.find.lib.data.InternetObserver;
import ul.fcul.lasige.find.lib.data.NeighborObserver;
import ul.fcul.lasige.find.lib.data.Packet;
import ul.fcul.lasige.find.lib.data.PacketObserver;
import ul.fcul.lasige.find.lib.data.ProtocolDefinitionParser;
import ul.fcul.lasige.find.lib.data.ProtocolRegistry;
import ul.fcul.lasige.find.lib.data.TokenStore;

/**
 * Created by hugonicolau on 03/11/2015.
 *
 * <p>This class provides an interface between 3rd-party apps and the FIND communication
 * platform.</p>
 *
 * <p>For 3rd-party apps it provides methods to bind/unbind to the FIND service (i.e. communication
 * platform) and register its protocols. It also allows client apps to send (enqueue) packets to be
 * sent and request discovery of neighbors.</p>
 */

public class FindConnector implements Handler.Callback {
    private static final String TAG = FindConnector.class.getSimpleName();

    // action used to start FIND platform
    private static final String START_FIND = "ul.fcul.lasige.find.action.START_FIND";
    // FIND's platform package. It is used to send explicit intents (needed for Android 5+)
    public static final String FIND_PACKAGE = "ul.fcul.lasige.find";

    // singleton instance
    private static FindConnector sInstance;
    // context of client app
    private final Context mContext;
    // connection with FIND service
    private final FindMessenger mMessenger;
    // utility class to store the mapping between protocols and tokens
    private final ProtocolRegistry mProtocolRegistry;

    // stores whether bind was successful or not; volatile is used to guarantee visibility
    private volatile boolean mPlatformAvailable;

    // maps protocol names to packet observer callbacks
    private final Map<String, PacketObserver.PacketCallback> mPacketCallbacks = new HashMap<>();
    // maps protocol names to packet observers
    private final Map<String, PacketObserver> mPacketObservers = new HashMap<>();

    // maps protocol names to neighbor observer callbacks
    private final Map<String, NeighborObserver.NeighborCallback> mNeighborCallbacks = new HashMap<>();
    // maps protocol names to neighbor observers
    private final Map<String, NeighborObserver> mNeighborObservers = new HashMap<>();

    // internet observer
    private InternetObserver mInternetObserver = null;

    /**
     * Enables access to singleton instance of FindConnector.
     *
     * @param context Context of client app.
     * @return FindConnector instance.
     */
    public static synchronized FindConnector getInstance(Context context) {
        if (sInstance == null) { sInstance = new FindConnector(context); }
        return sInstance;
    }

    // Constructor
    private FindConnector(Context context) {
        mContext = context.getApplicationContext();
        mMessenger = new FindMessenger(this);
        mProtocolRegistry = ProtocolRegistry.getInstance(mContext);
    }

    /**
     * Establish a connection between the client app and the FIND service. It should be called from
     * onStart().
     *
     * @return True if connection was successful, false otherwise.
     */
    public boolean bind(InternetObserver.InternetCallback callback) {
        // establish a connection with the service.
        Intent serviceIntent = new Intent(START_FIND);
        // need to set intent explicit
        serviceIntent.setPackage(FIND_PACKAGE);
        // bind
        mPlatformAvailable = mContext.bindService(serviceIntent, mMessenger, Context.BIND_AUTO_CREATE);

        if (mPlatformAvailable) {
            Log.d(TAG, "Successfully bound with the FIND platform");
            mMessenger.setApiKey(TokenStore.getApiKey(mContext));
            // internet observer
            mInternetObserver = new InternetObserver(callback);
            mInternetObserver.register();
            mMessenger.sendCommand(FindMessenger.MSG_INTERNET_CONNECTION); // request Internet state
        } else {
            Log.d(TAG, "Could not bound with the FIND platform");
            Toast.makeText(mContext, "The FIND platform is currently not installed.", Toast.LENGTH_SHORT).show();
            // TODO request installation
        }

        return mPlatformAvailable;
    }

    /**
     * Disconnects the client app from the FIND service. App no longer receives notifications for
     * registered protocols. It should be called from onStop().
     */
    public void unbind() {
        if (mPlatformAvailable) {
            Log.d(TAG, "Unbinding from FIND platform ...");
            // unbind from platform
            mMessenger.sendCommand(FindMessenger.MSG_RELEASE_INTERNET);
            mMessenger.sendCommand(FindMessenger.MSG_UNREGISTER_CLIENT);

            // unregister internet observer
            mInternetObserver.unregister();
            // unregister packet observers
            for (PacketObserver observer : mPacketObservers.values()) {
                observer.unregister();
            }
            // unregister neighbor observers
            for(NeighborObserver observer: mNeighborObservers.values()) {
                observer.unregister();
            }
        }
        mContext.unbindService(mMessenger);
    }

    /**
     * Registered protocols defined in XML with the FIND service. Client app will start receiving
     * packet notifications for registered protocols. This should be called from onStart() and
     * after a successful bind().
     *
     * @param resourceId The resource identifier of the XML file.
     * @param packetCallback The callback that will be called when new packets are received.
     * @param neighborCallback The callback that will be called on neighbor changes.
     *
     * @return Whether registration was successful. If not, this is probably because connection
     *         with the was not successfully established; try calling bind() before registering
     *         protocols.
     */
    public boolean registerProtocolsFromResources(int resourceId, PacketObserver.PacketCallback packetCallback,
                                                  NeighborObserver.NeighborCallback neighborCallback) {
        if (!mPlatformAvailable) {
            // connection was not established
            Log.d(TAG, "Platform not available");
            return false;
        }

        Resources res = mContext.getResources();
        // get parser that know how to read the XML
        ProtocolDefinitionParser parser = new ProtocolDefinitionParser(res.getXml(resourceId));

        Bundle protocolDescription;
        // for each protocol in XML
        while ((protocolDescription = parser.nextProtocol()) != null) {
            // get name
            final String protocolName = protocolDescription.getString(FindContract.Protocols.COLUMN_IDENTIFIER);

            // check if protocol is already registered
            final String protocolToken = mProtocolRegistry.getToken(protocolName);
            if (protocolToken != null) {
                // already registered, try to add a packet observer
                try {
                    // this happens when a client was still running while the Find platform
                    // got uninstalled and then reinstalled. This leads to a replacement of protocol
                    // tokens, which allow or deny access to the content provider data. If we indeed
                    // have stale tokens, we let them replace as if there was no registered token
                    // before - if the token still works, nothing else needs to be done
                    registerProtocolObserver(protocolName, protocolToken, packetCallback, neighborCallback);

                    // continue to next protocol
                    continue;
                } catch (SecurityException e) {
                    // something is wrong with the previously registered protocol, clean up and try
                    // again
                    Log.e(TAG, "Security exception in protocol: " + protocolName);
                    final PacketObserver observer = mPacketObservers.remove(protocolName);
                    if (observer != null) {
                        observer.unregister();
                    }
                    final NeighborObserver nObserver = mNeighborObservers.remove(protocolName);
                    if(nObserver != null) {
                        nObserver.unregister();
                    }
                }
            }

            mMessenger.sendCommand(FindMessenger.MSG_REGISTER_PROTOCOL, protocolDescription);
            mPacketCallbacks.put(protocolName, packetCallback);
            mNeighborCallbacks.put(protocolName, neighborCallback);
        }
        return true;
    }

    /**
     * Requests FIND service to start discovering new neighbors. If new neighbors are found, then
     * enqueued messages will be sent.
     */
    public void requestDiscovery() {
        if (mPlatformAvailable) {
            mMessenger.sendCommand(FindMessenger.MSG_ACTIVATE_DISCOVERY);
        }
        else {
            Log.d(TAG, "FIND platform not available. Make sure it is installed.");
        }
    }

    public void requestStart() {
        if (mPlatformAvailable) {
            mInternetObserver.register();
            mMessenger.sendCommand(FindMessenger.MSG_START_PLATFORM);
        }
        else {
            Log.d(TAG, "FIND platform not available. Make sure it is installed.");
        }
    }

    public void requestStop() {
        if (mPlatformAvailable) {
            mInternetObserver.unregister();
            mMessenger.sendCommand(FindMessenger.MSG_RELEASE_INTERNET);
            mMessenger.sendCommand(FindMessenger.MSG_STOP_PLATFORM);
        }
        else {
            Log.d(TAG, "FIND platform not available. Make sure it is installed.");
        }
    }

    /**
     * Gets the protocol token generated by the FIND platform.
     *
     * @param protocolName Protocol's name that was previously registered with the FIND platform.
     * @return Returns protocol token if it was successfully registered or null otherwise.
     */
    public String getProtocolToken(String protocolName) {
        return mProtocolRegistry.getToken(protocolName);
    }

    /**
     * Handles messages received from FIND service through ServiceConnection (FindMessenger).
     *
     * @param msg Messaged received from FIND service.
     * @return True if message was handled, false otherwise.
     */
    public boolean handleMessage(Message msg) {

        switch (msg.what) {
            // platform is give a change for the client app to register itself
            case FindMessenger.MSG_REQUEST_API_KEY: {
                Log.d(TAG, "Received from FIND service [MSG_REQUEST_API_KEY]");
                ApiKeyReceiver.requestApiKey(mContext);
                break;
            }

            // protocol was successfully registered
            case FindMessenger.MSG_REGISTER_PROTOCOL: {
                Log.d(TAG, "Received from FIND service [MSG_REGISTER_PROTOCOL]");
                final Bundle protocolNameAndToken = msg.peekData();
                // if data is valid
                if (protocolNameAndToken != null) {
                    // get name
                    final String protocolName = protocolNameAndToken.getString(FindMessenger.EXTRA_PROTOCOL_NAME);
                    // get token
                    final String protocolToken = protocolNameAndToken.getString(FindMessenger.EXTRA_PROTOCOL_TOKEN);

                    // register protocol locally
                    mProtocolRegistry.add(protocolName, protocolToken);

                    // get callback previously sent by client app
                    PacketObserver.PacketCallback packetCallback = mPacketCallbacks.remove(protocolName);
                    if (packetCallback == null) {
                        throw new IllegalStateException("No callback found to observe protocol " + protocolName);
                    } else {
                        NeighborObserver.NeighborCallback neighborCallback = mNeighborCallbacks.remove(protocolName);
                        if(neighborCallback == null) {
                            throw new IllegalStateException("No neighbor callback found to observe protocol " + protocolName);
                        }
                        else {
                            // register new protocol observer with callback, protocol name, and token
                            registerProtocolObserver(protocolName, protocolToken, packetCallback, neighborCallback);
                        }
                    }
                }
                break;
            }
            case FindMessenger.MSG_ACTIVATE_DISCOVERY: {
                final int active = msg.arg1;
                Log.d(TAG, "Received state update from FIND platform, platform is " + (active == 1 ? "on" : "off"));
                // TODO notify client app
                break;
            }
            case FindMessenger.MSG_INTERNET_CONNECTION: {
                final int connected = msg.arg1;
                mInternetObserver.onChange(connected == 1 ? true : false);
                break;
            }
            default: {
                // Not one of our messages, let someone else handle it.
                return false;
            }
        }

        // We handled the message
        return true;
    }

    /**
     * Registers a packet and neighbor observer (listeners) for a given protocol name and token.
     *
     * @param protocolName Name of the protocol.
     * @param protocolToken Token that was provided by the FIND service for this protocol.
     * @param packetCallback Class that will be called when receiving a new packet.
     * @param neighborCallback Class that will be called when changes to neighbors occur.
     */
    private void registerProtocolObserver(String protocolName, String protocolToken, PacketObserver.PacketCallback packetCallback,
                                          NeighborObserver.NeighborCallback neighborCallback) {
        // create new packet observer
        PacketObserver packetObserver = new PacketObserver(new Handler(), mContext, protocolToken, packetCallback);
        // map protocol name and observer
        mPacketObservers.put(protocolName, packetObserver);
        // register observer with FIND platform through content resolver
        packetObserver.register();
        // check existing packets
        packetObserver.onChange(true);

        // create new neighbor observer
        NeighborObserver neighborObserver = new NeighborObserver(new Handler(), mContext, protocolToken, neighborCallback);
        // map protocol name and observer
        mNeighborObservers.put(protocolName, neighborObserver);
        // register with FIND platform
        neighborObserver.register();
    }

    /**
     * <p>Enqueue data to be sent by the FIND service. This method should only be used when there is
     * a single protocol registered. Packet will be sent with the first registered protocol.</p>
     *
     * <p>serialized binary blob of data. How the serialization is done is completely up to the
     * client app - the platform does not need to know anything about the data structure.</p>
     *
     * <p>Data is sent if neighbors are currently connected or when new neighbors are discovered.</p>
     *
     * @param data Data to be sent.
     */
    public void enqueuePacket(byte[] data) {
        if (mPlatformAvailable) {
            final String protocolToken = mProtocolRegistry.getSingleToken();
            if(protocolToken == null) return;
            enqueuePacket(protocolToken, data);
        }
        else {
            Log.d(TAG, "Packet couldn't be enqueued to FIND platform. Make sure platform is installed.");
        }
    }

    /**
     * <p>Enqueue data to be sent by the FIND service to target node. This method should only be used when there is
     * a single protocol registered. Packet will be sent with the first registered protocol.</p>
     *
     * <p>serialized binary blob of data. How the serialization is done is completely up to the
     * client app - the platform does not need to know anything about the data structure.</p>
     *
     * <p>Data is sent if neighbor is currently connected or when neighbor is discovered.</p>
     *
     * @param data Data to be sent.
     * @param targetNodeId ID of target node (neighbor).
     */
    public void enqueuePacket(byte[] data, byte[] targetNodeId) {
        if (mPlatformAvailable) {
            final String protocolToken = mProtocolRegistry.getSingleToken();
            if(protocolToken == null) return;
            enqueuePacket(protocolToken, data, targetNodeId);
        }
        else {
            Log.d(TAG, "Packet couldn't be enqueued to FIND platform. Make sure platform is installed");
        }
    }

    /**
     * Enqueue data to be sent by the FIND service. Data is sent if neighbors are currently
     * connected or when new neighbors are discovered.
     *
     * <p>serialized binary blob of data. How the serialization is done is completely up to the
     * client app - the platform does not need to know anything about the data structure.</p>
     *
     * @param protocolToken Protocol token for the data.
     * @param data Data to be sent.
     */
    public void enqueuePacket(String protocolToken, byte[] data) {
        if (mPlatformAvailable) {
            // create content to be sent
            ContentValues values = new ContentValues();
            // set data
            values.put(FindContract.Packets.COLUMN_DATA, data);

            // data is sent through content resolver; FIND service is listening for changes
            // get URI for outgoing message
            Uri packetUri = FindContract.buildProtocolUri(FindContract.Packets.URI_OUTGOING, protocolToken);
            // get content resolver
            ContentResolver resolver = mContext.getContentResolver();
            // insert data to be sent
            resolver.insert(packetUri, values);

            Log.d(TAG, "Packet enqueued to FIND platform");
        }
        else {
            Log.d(TAG, "Packet couldn't be enqueued to FIND platform. Make sure platform is installed");
        }
    }

    /**
     * Enqueue data to be sent by the FIND service to a target node. Data is sent if neighbor is currently
     * connected or when neighbor is discovered.
     *
     * <p>serialized binary blob of data. How the serialization is done is completely up to the
     * client app - the platform does not need to know anything about the data structure.</p>
     *
     * @param protocolToken Protocol token for the data.
     * @param data Data to be sent.
     * @param nodeId ID of target node.
     */
    public void enqueuePacket(String protocolToken, byte[] data, byte[] nodeId) {
        if (mPlatformAvailable) {
            // create content to be sent
            ContentValues values = new ContentValues();
            // set data
            values.put(FindContract.Packets.COLUMN_DATA, data);
            // set target node
            values.put(FindContract.Packets.COLUMN_TARGET_NODE, nodeId);

            // data is sent through content resolver; FIND service is listening for changes
            // get URI for outgoing message
            Uri packetUri = FindContract.buildProtocolUri(FindContract.Packets.URI_OUTGOING, protocolToken);
            // get content resolver
            ContentResolver resolver = mContext.getContentResolver();
            // insert data to be sent
            resolver.insert(packetUri, values);

            Log.d(TAG, "Packet enqueued to FIND platform");
        }
        else {
            Log.d(TAG, "Packet couldn't be enqueued to FIND platform. Make sure platform is installed");
        }
    }

    public List<Packet> getOutgoingPackets() {
        if (mPlatformAvailable) {
            final String protocolToken = mProtocolRegistry.getSingleToken();
            return getOutgoingPackets(protocolToken);
        }
        else
        {
            Log.d(TAG, "Could not retrieve outgoing packets. Make sure platform is installed");
            return null;
        }
    }

    public List<Packet> getOutgoingPackets(String protocolToken) {
        if (mPlatformAvailable) {
            if(protocolToken == null) return new ArrayList<>();
            // get URI for outgoing message
            Uri packetUri = FindContract.buildProtocolUri(FindContract.Packets.URI_OUTGOING, protocolToken);
            // get content resolver
            ContentResolver resolver = mContext.getContentResolver();
            // insert data to be sent
            Cursor cursor = resolver.query(packetUri, FindContract.Packets.PROJECTION_DEFAULT, null, null, null);
            List<Packet> list = buildPacketListFromCursor(cursor);
            cursor.close();
            return list;
        }
        else
        {

            Log.d(TAG, "Could not retrieve outgoing packets. Make sure platform is installed");
            return null;
        }
    }

    private List<Packet> buildPacketListFromCursor(Cursor cursor) {
        ArrayList<Packet> list = new ArrayList<>();
        while (cursor.moveToNext()) {
            final Packet packet = Packet.fromCursor(cursor);
            list.add(packet);
        }
        return list;
    }

    public void acquireInternetLock() {
        if(mPlatformAvailable) {
            mMessenger.sendCommand(FindMessenger.MSG_ACQUIRE_INTERNET);
        }
        else {
            Log.d(TAG, "FIND platform not available. Make sure it is installed.");
        }
    }

    public void releaseInternetLock() {
        if(mPlatformAvailable) {
            Log.d(TAG, "sending release message");
            mMessenger.sendCommand(FindMessenger.MSG_RELEASE_INTERNET);
        }
        else {
            Log.d(TAG, "FIND platform not available. Make sure it is installed.");
        }
    }

}
