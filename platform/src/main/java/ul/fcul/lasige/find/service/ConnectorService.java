package ul.fcul.lasige.find.service;

import android.app.Service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;

import ul.fcul.lasige.find.beaconing.BeaconingManager;
import ul.fcul.lasige.find.data.ClientImplementation;
import ul.fcul.lasige.find.lib.service.FindMessenger;

/**
 *
 * Endpoint of the FindConnector component used by FIND client applications. This service allows
 * a client to register itself and the protocols it supports, as well as to trigger some operation
 * modes of the FIND platform.
 * <p>
 * This service is only meant to be bound to by client apps. Other platform components can directly
 * use the {@link SupervisorService}.
 *
 * Created by hugonicolau on 04/11/2015.
 */
public class ConnectorService extends Service
        implements Handler.Callback, SupervisorService.Callback, BeaconingManager.InternetCallback {
    private static final String TAG = ConnectorService.class.getSimpleName();

    // maps app tokens of bound clients to reply messengers.
    private final HashMap<String, Messenger> mConnectedClients = new HashMap<>();

    // handles messages from bound client apps.
    private Messenger mMessenger = new Messenger(new Handler(ConnectorService.this));

    // supervisor service object
    private SupervisorService mSupervisor;
    // supervisor service connection
    private ServiceConnection mSupervisorConnection;

    /**
     * When the Connector Service is created (by a client application request) it binds to Supervisor Service.
     *
     * @see SupervisorService
     */
    @Override
    public void onCreate() {
        super.onCreate();

        // supervisor service connection
        mSupervisorConnection = new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mSupervisor = null;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mSupervisor = ((SupervisorService.SupervisorBinder) service).getSupervisor();
                // set supervisor callback to listen for state changes
                mSupervisor.addCallback(ConnectorService.this);
                // set internet callback to listen to internet connectivity changes
                mSupervisor.getBeaconingManager().addInternetCallback(ConnectorService.this);
            }
        };
        // binds to supervisor service
        SupervisorService.bindSupervisorService(this, mSupervisorConnection);
    }

    @Override
    public void onDestroy() {
        // remove callbacks
        mSupervisor.getBeaconingManager().removeInternetCallback(this);
        // make sure internet locks are released
        mSupervisor.getBeaconingManager().resetInternetLock();

        // unbind from supervisor service
        unbindService(mSupervisorConnection);
        mSupervisorConnection = null;

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return mMessenger.getBinder(); }

    /*
     * Supervisor callback for state changes
     */
    @Override
    public void onActivationStateChanged(boolean activated) {
        // notify all connected clients
        for (String clientApiKey : mConnectedClients.keySet()) {
            reply(clientApiKey, FindMessenger.MSG_ACTIVATE_DISCOVERY, activated);
        }
    }

    /*
     * Beaconing callback for Internet connectivity changes
     */
    @Override
    public void onInternetConnection(boolean connected) {
        // notify all connected clients
        for (String clientApiKey : mConnectedClients.keySet()) {
            reply(clientApiKey, FindMessenger.MSG_INTERNET_CONNECTION, connected);
        }
    }


    /**
     * Handles messages from client applications.
     * @param msg Received message.
     * @return true if message was handled, false otherwise.
     * @see ul.fcul.lasige.find.lib.service.FindConnector
     * @see FindMessenger
     */
    @Override
    public boolean handleMessage(Message msg) {
        // get data
        final Bundle extraData = msg.getData();

        // get client app's api key (previously issued during installation)
        final String apiKey = extraData.getString(FindMessenger.EXTRA_API_KEY);
        if (apiKey == null) {
            // this service does not work without API keys
            // give a chance for client app to request api key
            tryReplyToLetClientRequestApiKey(msg.replyTo);
            return true;
        } else {
            // clean extra data
            extraData.remove(FindMessenger.EXTRA_API_KEY);
        }

        // get platform's application object
        final FindApp app = (FindApp) getApplication();
        // get app name from application registry
        final String appName = app.getApplicationRegistry().getPackageFromToken(apiKey);
        if (appName == null) {
            // api token does not match a registered client application
            Log.w(TAG, "API key " + apiKey + " does not belong to a registered client app!");
            // give a chace for the client app to request api key
            tryReplyToLetClientRequestApiKey(msg.replyTo);
            return true;
        }

        switch (msg.what) {
            // message received when client application connects with platform
            case FindMessenger.MSG_REGISTER_CLIENT: {
                final Messenger replyTo = msg.replyTo;
                if (replyTo != null) {
                    // register client application
                    // from now on, app will receive notifications about platform state and
                    // internet connectivity
                    registerClient(appName, apiKey, replyTo);
                }
                break;
            }

            // message received when client application disconnects from platform (unbind)
            case FindMessenger.MSG_UNREGISTER_CLIENT: {
                unregisterClient(appName, apiKey);
                break;
            }

            // message received to register client app's protocol. Client app needs to be registered beforehand.
            case FindMessenger.MSG_REGISTER_PROTOCOL: {
                if (!extraData.isEmpty()) {
                    // register protocol for the client application
                    registerProtocol(apiKey, extraData);
                }
                break;
            }

            // message received when client app request the platform to find nearby neighbors (i.e. send beacons)
            case FindMessenger.MSG_ACTIVATE_DISCOVERY: {
                Log.d(TAG, "client requested beaconing");
                // is supervisor in running state?
                final boolean granted = mSupervisor.requestBeaconing();
                // reply to client app
                reply(apiKey, FindMessenger.MSG_ACTIVATE_DISCOVERY, granted);
                break;
            }

            // activates the FIND platform; SupervisorService will transition to running state
            case FindMessenger.MSG_START_PLATFORM: {
                Log.d(TAG, "client requested the FIND platform to start");
                mSupervisor.activateFIND();
                break;
            }

            // deactivates the FIND platform; SupervisorService will transition to idle state
            case FindMessenger.MSG_STOP_PLATFORM: {
                Log.d(TAG, "client requested the FIND platform to stop");
                mSupervisor.deactivateFIND();
                break;
            }

            // client app acquires an Internet lock; the platform will not try to change from current WiFi connection
            case FindMessenger.MSG_ACQUIRE_INTERNET: {
                Log.d(TAG, "client requested Internet lock");
                mSupervisor.getBeaconingManager().acquireInternetLock(appName);
                break;
            }

            // client app releases Internet lock
            case FindMessenger.MSG_RELEASE_INTERNET: {
                Log.d(TAG, "client requested Internet lock release");
                mSupervisor.getBeaconingManager().releaseInternetLock(appName);
                break;
            }

            case FindMessenger.MSG_INTERNET_CONNECTION: {
                Log.d(TAG, "client requested internet state");
                // reply whether we currently have internet access
                reply(apiKey, FindMessenger.MSG_INTERNET_CONNECTION, mSupervisor.getBeaconingManager().hasInternetAccess());
            }
            default: {
                Log.d(TAG, "received an unrecognized message");
                return false;
            }
        }

        return true;
    }

    /**
     * Register client application by adding it to the currently connected clients. The platform can now send messages
     * to the client application, namely replies to requests and updates on platform state and internet connectivity.
     *
     * @param appName Client application's name.
     * @param apiKey Client application's api key.
     * @param replyTo Client application's Messenger object.
     */
    private void registerClient(String appName, String apiKey, Messenger replyTo) {
        final Messenger prev = mConnectedClients.put(apiKey, replyTo);
        if (prev == null) {
            Log.v(TAG, "client app " + appName + " has connected.");
        }
    }

    /**
     * Unregister client application. It will no longer receive messages/updates from the platform.
     * @param appName Name of client application.
     * @param apiKey Client app's API key.
     */
    private void unregisterClient(String appName, String apiKey) {
        final Messenger prev = mConnectedClients.remove(apiKey);
        if (prev != null) {
            Log.v(TAG, "client app " + appName + " has disconnected.");
        }
    }

    /**
     * Registers protocol for given API key. Replies to client app to a unique protocol access token
     * that is required to publish data and require data updates.
     *
     * @param apiKey Client application's API key
     * @param protocolDescription Protocol description.
     */
    private void registerProtocol(String apiKey, Bundle protocolDescription) {
        Log.d(TAG, "client app requested to register protocol");
        // get platform application
        final FindApp app = (FindApp) getApplication();
        // register protocol in registry
        final ClientImplementation impl = app.getProtocolRegistry().registerProtocol(apiKey, protocolDescription);

        // a ClientImplementation is returned
        if (impl != null) {
            Log.d(TAG, "protocol was registered: " + impl.getProtocolName() + " token: " + impl.getToken());
            // builds ClientImplementation data structure
            final Bundle replyData = new Bundle();
            replyData.putString(FindMessenger.EXTRA_PROTOCOL_NAME, impl.getProtocolName());
            replyData.putString(FindMessenger.EXTRA_PROTOCOL_TOKEN, impl.getToken());
            // reply to client app (needs to be previously registered)
            reply(apiKey, FindMessenger.MSG_REGISTER_PROTOCOL, replyData);
        }
    }

    /**
     * Send a boolean value to client app.
     * @param apiKey Client app's API key.
     * @param msgCode Message code.
     * @param flag Boolean value.
     */
    private void reply(String apiKey, int msgCode, boolean flag) {
        reply(apiKey, msgCode, flag, null);
    }

    /**
     * Send a data structure to client app.
     * @param apiKey Client app's API key.
     * @param msgCode Message code.
     * @param data Data structure.
     */
    private void reply(String apiKey, int msgCode, Bundle data) {
        reply(apiKey, msgCode, null, data);
    }

    /**
     * Send a data structure and boolean value to client app. Client needs to be previously registered.
     *
     * <p>Boolean value is store in arg1 as an int ( 1 - true; 0 - false ) and data structure is stored as a data bundle.</p>
     *
     * @param apiKey Client app's API key.
     * @param msgCode Message code.
     * @param flag Boolean value.
     * @param dataBundle Data structure.
     */
    private void reply(String apiKey, int msgCode, Boolean flag, Bundle dataBundle) {
        Message reply = Message.obtain();
        // set message code
        reply.what = msgCode;
        // set boolean value
        reply.arg1 = (flag != null && flag) ? 1 : 0;
        // set data
        reply.setData(dataBundle);

        try {
            // send message
            mConnectedClients.get(apiKey).send(reply);
        } catch (Exception e) {
            // client is not connected anymore
            Log.d(TAG, "client is not connected anymore: " + apiKey);
            unregisterClient(null, apiKey);
        }
    }

    /**
     * Reply to an application that does not have an API key yet. This gives a change to the
     * client application to request an API key.
     * @param replyTo Messenger object of client app.
     * @see ul.fcul.lasige.find.lib.service.FindConnector
     */
    private void tryReplyToLetClientRequestApiKey(Messenger replyTo) {
        Log.d(TAG, "client does not have an API key yet, give it a change to register");
        if (replyTo != null) {
            Message reply = Message.obtain();
            reply.what = FindMessenger.MSG_REQUEST_API_KEY;
            try {
                // send message
                replyTo.send(reply);
            } catch (Exception e) {
                // Client is not connected anymore, but hasn't been registered before, either.
                Log.d(TAG, "Client is not connected anymore but hasn't been registered before, either");
            }
        }
    }
}
