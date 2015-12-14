package ul.fcul.lasige.findvictim.gcm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import ul.fcul.lasige.findvictim.app.VictimApp;
import ul.fcul.lasige.findvictim.data.Alert;
import ul.fcul.lasige.findvictim.data.DatabaseHelper;
import ul.fcul.lasige.findvictim.sensors.SensorsService;

/**
 * Created by hugonicolau on 03/12/15.
 */
public class GcmScheduler {
    private static final String TAG = GcmScheduler.class.getSimpleName();

    public static final String ACTION_SCHEDULE_START = "ul.fcul.lasige.findvictim.action.ALARM_SCHEDULE_START";
    public static final String ACTION_SCHEDULE_STOP = "ul.fcul.lasige.findvictim.action.ALARM_SCHEDULE_STOP";
    public static final String EXTRA_ALERT_NAME = "name";

    // singleton instance
    private static GcmScheduler sInstance = null;

    // alarm manager
    AlarmManager mAlarmManager;
    private PendingIntent mStartSensorsIntent = null;
    private PendingIntent mStopSensorsIntent = null;

    private GcmScheduler(Context context) {
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    public static GcmScheduler getInstance(Context context) {
        if(sInstance == null) sInstance = new GcmScheduler(context);
        return sInstance;
    }

    public void scheduleAlarm(Context context, Alert alert) {

        // schedule start alarm
        Intent startIntent = new Intent(context, GcmSchedulerReceiver.class);
        startIntent.setAction(ACTION_SCHEDULE_START);
        startIntent.putExtra(EXTRA_ALERT_NAME, alert.getName());

        mStartSensorsIntent = PendingIntent.getBroadcast(context, 0,
                startIntent, PendingIntent.FLAG_UPDATE_CURRENT); // TODO alarmmanager only guarantees one start alarm

        // get starting time
        Date date = alert.getDate();
        if(date != null) {
            mAlarmManager.set(AlarmManager.RTC_WAKEUP, date.getTime(), mStartSensorsIntent);
            Log.d(TAG, "Alert scheduled to start at " + date.toString());
        }

        // schedule stop alarm
        Intent stopIntent = new Intent(context, GcmSchedulerReceiver.class);
        stopIntent.setAction(ACTION_SCHEDULE_STOP);
        stopIntent.putExtra(EXTRA_ALERT_NAME, alert.getName());

        mStopSensorsIntent = PendingIntent.getBroadcast(context, 1234,
                stopIntent, PendingIntent.FLAG_UPDATE_CURRENT); // TODO alarmmanager only guarantees one stop alarm

        // get stopping time
        long duration = alert.getDuration();
        if(duration != -1) {
            mAlarmManager.set(AlarmManager.RTC_WAKEUP, date.getTime() + duration, mStopSensorsIntent);
            Log.d(TAG, "Alert scheduled to stop at " + new Date(date.getTime() + duration).toString());
        }
    }

    public void cancelAlarm(Context context) {
        if (mStartSensorsIntent != null) {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarmManager.cancel(mStartSensorsIntent);
            mStartSensorsIntent = null;
            Log.v(TAG, "Cancelled start alarm");
        }

        if (mStopSensorsIntent != null) {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarmManager.cancel(mStopSensorsIntent);
            mStopSensorsIntent = null;
            Log.v(TAG, "Cancelled stop alarm");
        }

        // stop sensors service
        VictimApp app = (VictimApp)context.getApplicationContext();
        app.stopSensors();
    }

    public static class GcmSchedulerReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Received alarm");

            if(intent != null) {
                String action = intent.getAction();

                if(action != null && action.equalsIgnoreCase(ACTION_SCHEDULE_START)) {
                    Log.d(TAG, "Received alarm to start sensors");

                    // update alert status
                    Alert.Store.updateAlertStatus(DatabaseHelper.getInstance(context).getWritableDatabase(),
                            intent.getStringExtra(EXTRA_ALERT_NAME), Alert.STATUS.ONGOING);

                    // start sensors service
                    VictimApp app = (VictimApp)context.getApplicationContext();
                    app.starSensors();

                }
                else if(action != null && action.equalsIgnoreCase((ACTION_SCHEDULE_STOP))) {
                    Log.d(TAG, "Received alarm to stop sensors");

                    // update alert status
                    Alert.Store.updateAlertStatus(DatabaseHelper.getInstance(context).getWritableDatabase(),
                            intent.getStringExtra(EXTRA_ALERT_NAME), Alert.STATUS.STOPPED);

                    // stop sensors service
                    VictimApp app = (VictimApp)context.getApplicationContext();
                    app.stopSensors();
                }
            }
        }
    }
}
