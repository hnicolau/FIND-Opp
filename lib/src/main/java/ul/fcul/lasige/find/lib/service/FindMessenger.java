package ul.fcul.lasige.find.lib.service;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayDeque;

/**
 * This class holds a ServiceConnection and a handler to communicate with FIND service. It is used
 * by the FindConnector Singleton class.
 *
 * Created by hugonicolau on 03/11/2015.
 */
public class FindMessenger implements ServiceConnection {
    private static final String TAG = FindMessenger.class.getSimpleName();

    // priority of the message to be sent to the FIND service. This is not related to the priority
    // of network communication
    protected enum MessagePriority {
        LOW, MEDIUM, HIGH
    }

    /**
     * Message codes
     */
    // used to request a token for the client app
    public static final int MSG_REQUEST_API_KEY = 1;
    // used to register a client app
    public static final int MSG_REGISTER_CLIENT = 2;
    // used to unregister a client app
    public static final int MSG_UNREGISTER_CLIENT = 3;
    // used to register a protocol
    public static final int MSG_REGISTER_PROTOCOL = 4;
    // used to start the Supervisor service
    public static final int MSG_START_PLATFORM = 5;
    // used to stop the Supervisor service
    public static final int MSG_STOP_PLATFORM = 6;
    // used to keep the platform on the current network
    public static final int MSG_ACQUIRE_INTERNET = 7;
    // used to keep the platform on the current network
    public static final int MSG_RELEASE_INTERNET = 8;
    // used to start the discovering of new neighbors and notify client of platform state (on/off)
    public static final int MSG_ACTIVATE_DISCOVERY = 23;
    // used to notify clients of internet connection
    public static final int MSG_INTERNET_CONNECTION = 24;

    /**
     * Bundle parameters used for communication with FIND service
     */
    public static final String EXTRA_API_KEY = "api_key";
    public static final String EXTRA_PROTOCOL_NAME = "protocol_name";
    public static final String EXTRA_PROTOCOL_TOKEN = "protocol_token";

    // messenger used for cross-process communication (client app - FIND service)
    private final Messenger mOwnMessenger;
    // app token received from FIND service
    private String mApiKey;
    // messenger returned by the FIND service when a connection is established
    private Messenger mServiceMessenger;

    /**
     * Message sending state
     */
    // a queue for all messages that need to be sent to FIND service
    private static final ArrayDeque<Message> sMessageQueue = new ArrayDeque<>();
    // runnable that will be used to send messages from the queue
    private MessageSenderRunnable mMessageSenderRunnable;

    // constructor
    protected FindMessenger(Handler.Callback callback) {
        mOwnMessenger = new Messenger(new android.os.Handler(callback));
    }

    /**
     * Stores the api key (token) received from the FIND service.
     *
     * @param newKey Key to be stored.
     */
    protected void setApiKey(String newKey) { mApiKey = newKey; }

    /**
     * Send message to FIND service via IDL interface. No additional data is sent.
     *
     * @param msgCode Command/Message code to be sent.
     */
    protected void sendCommand(int msgCode) {
        sendCommand(msgCode, new Bundle(), null, MessagePriority.MEDIUM);
    }

    /**
     * Send message to FIND service via IDL interface.
     *
     * @param msgCode Command/Message code to be sent.
     * @param data Additional data that accompanies the command.
     */
    protected void sendCommand(int msgCode, Bundle data) {
        sendCommand(msgCode, data, null, MessagePriority.MEDIUM);
    }

    /**
     * Send message to FIND service via IDL interface.
     *
     * @param msgCode Command/Message code to be sent.
     * @param data Additional data that accompanies the command.
     * @param replyTo Messenger that FIND service should reply to.
     * @param priority Message priority.
     *
     * @see ul.fcul.lasige.find.lib.service.FindMessenger.MessagePriority
     */
    private void sendCommand(int msgCode, Bundle data, Messenger replyTo, MessagePriority priority) {
        // add client token to the message, otherwise it won't be handled by FIND service
        data.putString(EXTRA_API_KEY, mApiKey);

        // builds message
        Message msg = Message.obtain();
        msg.what = msgCode;
        msg.setData(data);
        msg.replyTo = replyTo;

        // lock access to queue
        synchronized (sMessageQueue) {
            switch (priority) {
                case HIGH: {
                    // if priority high, add to the top of the queue
                    sMessageQueue.addFirst(msg);
                    break;
                }

                case MEDIUM:
                case LOW: {
                    sMessageQueue.add(msg);
                    break;
                }
            }
            // notifies all threads waiting for changes in the queue
            sMessageQueue.notifyAll();
        }
    }

    /**
     * Registers client app with the FIND service. This messages is sent before all other. This is
     * usually called after a connection has been established. We will then receive a reply from
     * the platform to request an API key (client token - MSG_REQUEST_API_KEY). This will be handled
     * by the mOwnMessage callback (FindConnector).
     */
    private void registerClient() {
        sendCommand(MSG_REGISTER_CLIENT, new Bundle(), mOwnMessenger, MessagePriority.HIGH);
    }

    /*
     * SERVICECONNECTION INTERFACE METHODS
     */

    /**
     * This method is called when a connection is established with the FIND service.
     *
     * @param className Component name to which a connection was made.
     * @param service Service object to which a connection was established.
     */

    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
        Log.d(TAG, "Service connected!");
        // This is called when the connection with the service has been
        // established, giving us the service object we can use to
        // interact with the service. We are communicating with our
        // service through an IDL interface, so get a client-side
        // representation of that from the raw service object.
        mServiceMessenger = new Messenger(service);

        // registers the client app; a message will be sent to the FIND service
        registerClient();

        // start sender thread
        mMessageSenderRunnable = new MessageSenderRunnable();
        new Thread(mMessageSenderRunnable).start();
    }

    /**
     * This is called when the connection with the service has been unexpectedly disconnected;
     * that is, its process crashed.
     *
     * @param componentName Component name of the disconnected service.
     */
    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        // stops thread from sending new messages
        mMessageSenderRunnable.terminate();
        mMessageSenderRunnable = null;
    }

    /**
     * This class is a sender thread that reads from a queue and sends messages to the FIND service.
     */
    private class MessageSenderRunnable implements Runnable {
        // indicates whether the thread is supposed to be running. Volatile is to guarantee
        // visibility to changes
        private volatile boolean running = true;

        /**
         * Stops thread from sending new messages to FIND service; thread will attempt to send
         * all messages in queue and then stop
         */
        public void terminate() {
            running = false;
        }

        @Override
        public void run() {
            // always running, unless terminate is called; if so, tries to empty queue before stopping
            while (running || !sMessageQueue.isEmpty()) {
                // lock access to queue
                synchronized (sMessageQueue) {
                    // send all messages
                    while (!sMessageQueue.isEmpty()) {
                        // get the first message
                        Message msg = sMessageQueue.poll();
                        if (msg != null) {
                            try {
                                // send
                                mServiceMessenger.send(msg);
                            } catch (RemoteException e) {
                                // in this case the service has crashed before we could even
                                // do anything with it; we can count on soon being
                                // disconnected (and then reconnected if it can be restarted)
                                // so there is no need to do anything here.
                                Log.w(TAG, "Find service is down, terminating Messenger");
                                sMessageQueue.addFirst(msg);
                                terminate();
                                break;
                            }
                        }
                    }

                    try {
                        // waits for new messages via notify()
                        sMessageQueue.wait();
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Exception while waiting to be notified, we will continue sending messages: " + e.getMessage());
                    }
                }
            }
        }
    }

}
