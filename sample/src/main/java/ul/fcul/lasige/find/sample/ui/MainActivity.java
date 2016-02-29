package ul.fcul.lasige.find.sample.ui;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import java.util.Locale;
import java.util.Set;

import ul.fcul.lasige.find.lib.data.InternetObserver;
import ul.fcul.lasige.find.lib.data.Neighbor;
import ul.fcul.lasige.find.lib.data.NeighborObserver;
import ul.fcul.lasige.find.lib.data.Packet;
import ul.fcul.lasige.find.lib.data.PacketObserver;
import ul.fcul.lasige.find.lib.service.FindConnector;
import ul.fcul.lasige.find.sample.R;
import ul.fcul.lasige.find.sample.data.DatabaseHelper;
import ul.fcul.lasige.find.sample.data.Message;

public class MainActivity extends AppCompatActivity implements PacketObserver.PacketCallback,
        NeighborObserver.NeighborCallback, InternetObserver.InternetCallback {
    private static final String TAG = MainActivity.class.getSimpleName();

    // app-level variables
    private ListView mMessageList;
    private SimpleCursorAdapter mAdapter;
    SQLiteDatabase mDb;

    // communication (FIND) variables
    private FindConnector mConnector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // setup database
        DatabaseHelper dbHelper = new DatabaseHelper(this);
        mDb = dbHelper.getWritableDatabase();

        // populate list
        // get all messages
        Cursor cursor = Message.Store.fetchAllMessages(mDb);
        String[] cols = new String[] {
                Message.Store.COLUMN_CONTENT,
                Message.Store.COLUMN_SENDER
        };
        int[] to = new int[] {
                R.id.messageContent, R.id.messageMeta
        };

        // configure how to populate the list view through an adapter
        mAdapter = new SimpleCursorAdapter(this, R.layout.message, cursor, cols, to, 0);
        mAdapter.setViewBinder(new SimpleCursorAdapter.ViewBinder() {
            @Override
            public boolean setViewValue(View v, Cursor cursor, int columnIndex) {
                TextView view = (TextView) v;
                String content = cursor.getString(columnIndex);

                if (cursor.getColumnName(columnIndex).equals(Message.Store.COLUMN_SENDER)) {
                    int timeColIndex = cursor.getColumnIndex(Message.Store.COLUMN_TIME_SENT);
                    int flags = DateUtils.FORMAT_SHOW_TIME
                            | DateUtils.FORMAT_SHOW_DATE
                            | DateUtils.FORMAT_SHOW_YEAR
                            | DateUtils.FORMAT_NUMERIC_DATE;

                    String formatted = DateUtils.formatDateTime(
                            MainActivity.this, 1000 * cursor.getLong(timeColIndex), flags);
                    content = content + " at " + formatted;
                }

                view.setText(content);
                return true;
            }
        });

        // set adapter
        mMessageList = (ListView) findViewById(R.id.messagesList);
        mMessageList.setAdapter(mAdapter);

        // get FIND platform connector
        mConnector = FindConnector.getInstance(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // FIND
        Log.d(TAG, "Trying to bind with FIND platform ...");
        if (mConnector.bind(this)) {
            Log.d(TAG, "Bind was successful");
            mConnector.registerProtocolsFromResources(R.xml.protocols, this, this);
        }
    }

    @Override
    protected void onPause() {
        // FIND
        Log.d(TAG, "Unbinding with FIND platform");
        mConnector.unbind();
        super.onPause();
    }

    // called on button click (see activity_main.xml)
    public void sendMessage(View view) {
        EditText inputField = (EditText) findViewById(R.id.input_newMessage);
        String message = inputField.getText().toString().trim();

        if (!message.isEmpty()) {
            Message newMsg = new Message();
            newMsg.sender = "Me Myself and I";
            newMsg.content = message;

            long currentTime = System.currentTimeMillis() / 1000L;
            newMsg.timeSent = currentTime;
            newMsg.timeReceived = currentTime;

            Message.Store.addMessage(mDb, newMsg);
            // FIND
            mConnector.enqueuePacket(message.getBytes());
            mConnector.requestDiscovery(); // try to find neighbors (optional)
            refreshCursor();
        }

        inputField.setText(null);
    }

    private void refreshCursor() {
        Cursor newCursor = Message.Store.fetchAllMessages(mDb);
        mAdapter.changeCursor(newCursor);
    }

    /*
     * FIND - PACKET CALLBACK
     */
    @Override
    public void onPacketReceived(Packet packet) {
        Log.d(TAG, "Packet received");
        Message newMsg = new Message();
        newMsg.content = new String(packet.getData());
        newMsg.timeSent = packet.getTimeReceived();
        newMsg.timeReceived = packet.getTimeReceived();

        final String sender = packet.getSourceNodeAsHex();
        newMsg.sender =
                sender != null ? sender.substring(0, 12).toLowerCase(Locale.US) : "Anonymous";

        Message.Store.addMessage(mDb, newMsg);
        refreshCursor();
    }

    /*
     * FIND - NEIGHBOR CALLBACK
     */
    @Override
    public void onNeighborConnected(Neighbor currentNeighbor) {
        // this is called everytime we find a new neighbor in the current beaconing stage
        Log.d(TAG, "Neighbor CONNECTED");
    }

    @Override
    public void onNeighborDisconnected(Neighbor recentNeighbor) {
        // called when neighbor is unreachable for 2 beaconing periods or a maximum of 20 minutes
        Log.d(TAG, "Neighbor DISCONNECTED");
    }

    @Override
    public void onNeighborsChanged(Set<Neighbor> currentNeighbors) {
        // this is called on every beaconing period, twice
        // also, everytime we receive a beacon from a neighbor
        // sometimes there are no practical changes;
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }
        else if(id == R.id.action_start) {
            mConnector.requestStart();
        }
        else if(id == R.id.action_stop) {
            mConnector.requestStop();
        }

        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onInternetConnection(boolean connected) {
        Log.d(TAG, "OnInternetConnection: " + connected);
        if(connected) {
            Log.d(TAG, "Acquiring lock");
            // example on how to attempt to acquire internet lock from platform
          /*  mConnector.acquireInternetLock();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(120000);
                        Log.d(TAG, "Going to release lock");
                        mConnector.releaseInternetLock();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }).start();*/
        }
    }
}
