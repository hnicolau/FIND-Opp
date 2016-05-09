package ul.fcul.lasige.findvictim.gcm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.gcm.GcmListenerService;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import ul.fcul.lasige.findvictim.app.VictimApp;
import ul.fcul.lasige.findvictim.data.Alert;
import ul.fcul.lasige.findvictim.data.DatabaseHelper;
import ul.fcul.lasige.findvictim.sensors.SensorsService;

/**
 * Created by hugonicolau on 27/11/15.
 */
public class MyGcmListenerService extends GcmListenerService {
    private static final String TAG = MyGcmListenerService.class.getSimpleName();

    private static final String KEY_GCM_TYPE = "gcm_type";
    private static final String KEY_GCM_ALERT_MODE = "mode";
    private static final String KEY_GCM_ALERT_NAME = "name";
    private static final String KEY_GCM_ALERT_DESCRIPTION = "description";
    private static final String KEY_GCM_ALERT_ID = "alertID";
    private static final String KEY_GCM_ALERT_DATE = "date";
    private static final String KEY_GCM_ALERT_DURATION = "duration";
    private static final String KEY_GCM_ALERT_LAT_S = "latS";
    private static final String KEY_GCM_ALERT_LON_S = "lonS";
    private static final String KEY_GCM_ALERT_LAT_E = "latE";
    private static final String KEY_GCM_ALERT_LON_E = "lonE";

    private final int ALERT = 1;
    private final int STOP = 3;
    private final int MODE = 4;

    /**
     * Called when message is received.
     *
     * @param from SenderID of the sender.
     * @param data Data bundle containing message data as key/value pairs.
     *             For Set of keys use data.keySet().
     */
    @Override
    public void onMessageReceived(String from, Bundle data) {
        Log.d(TAG, "GCM Message Received from: " + from);
        Log.e(TAG,"GCM Message Received from: " +data.getString(KEY_GCM_TYPE));

        if (from.startsWith("/topics/")) {
            // message received from some topic.
        } else {
            Log.e(TAG,data.getString(KEY_GCM_TYPE));
            // normal downstream message.
            int type = Integer.valueOf(data.getString(KEY_GCM_TYPE));
            Log.d(TAG, "Type: " + type);

            switch(type) {
                case ALERT:
                    startFindVictimService(data);
                    break;
                case STOP:
                    stopFindVictimService(data);
                    break;
                case MODE:
                    changeMode(data);
                    break;
            }

        }
    }

    private void startFindVictimService(Bundle data) {
        Log.d(TAG, "Mode: " + data.getString("mode"));
        Log.d(TAG, "Name: " + data.getString("name"));
        Log.d(TAG, "Description: " + data.getString("description"));
        Log.d(TAG, "AlertID: " + data.getString("alertID"));
        Log.d(TAG, "Date: " + data.getString("date"));
        Log.d(TAG, "Duration: " + data.getString("duration"));
        Log.d(TAG, "LatS: " + data.getString("latS"));
        Log.d(TAG, "LonS: " + data.getString("lonS"));
        Log.d(TAG, "LatE: " + data.getString("latE"));
        Log.d(TAG, "LonE: " + data.getString("lonE"));

        String name = data.getString(KEY_GCM_ALERT_NAME);
        String description = data.getString(KEY_GCM_ALERT_DESCRIPTION);
        int alertID = Integer.valueOf(data.getString(KEY_GCM_ALERT_ID));
        String date = data.getString(KEY_GCM_ALERT_DATE);
        String duration = data.getString(KEY_GCM_ALERT_DURATION);
        String latS = data.getString(KEY_GCM_ALERT_LAT_S);
        String lonS = data.getString(KEY_GCM_ALERT_LON_S);
        String latE = data.getString(KEY_GCM_ALERT_LAT_E);
        String lonE = data.getString(KEY_GCM_ALERT_LON_E);

        // calculate end time
        long durationL = Long.parseLong(duration);
        long durationInMillis = durationL * 60 * 1000;

        // check if the simulation has already ended
        DateFormat formatter = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date dateFor;
        try {
            dateFor = formatter.parse(date);
            if(System.currentTimeMillis() > dateFor.getTime() + durationInMillis) {
                Log.d(TAG, "The simulation has already ended");
                return;
            }
        } catch (ParseException e) {
            e.printStackTrace();
            return;
        }

        // bad code incoming
        if(latS == null)
            return;

        // store alert (persistently)
        Alert alert = new Alert(name, description, alertID, date, duration, latS, lonS, latE, lonE, Alert.STATUS.SCHEDULED);
        Alert.Store.addAlert(DatabaseHelper.getInstance(getApplicationContext()).getReadableDatabase(), alert);

        // schedule start
        GcmScheduler.getInstance(getApplicationContext()).scheduleAlarm(getApplicationContext(), alert);
    }

    private void stopFindVictimService(Bundle data) {
        // cancel alarm
        GcmScheduler.getInstance(getApplicationContext()).cancelAlarm(getApplicationContext(),   Integer.valueOf(data.getString(KEY_GCM_ALERT_ID)));
    }

    private void changeMode(Bundle data) {
    }
}
