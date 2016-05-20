package com.example.unzi.findalert.gcm;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;

import com.example.unzi.findalert.R;
import com.example.unzi.findalert.data.Alert;
import com.example.unzi.findalert.data.DatabaseHelper;
import com.example.unzi.findalert.sensors.LocationSensor;
import com.example.unzi.findalert.ui.AlertActivity;
import com.example.unzi.findalert.ui.RegisterInFind;
import com.example.unzi.findalert.utils.DeviceUtils;
import com.example.unzi.findalert.utils.PositionUtils;
import com.example.unzi.findalert.webservice.WebLogging;

import java.util.Date;

/**
 * Created by hugonicolau on 03/12/15.
 */
public class GcmScheduler {
    private static final String TAG = GcmScheduler.class.getSimpleName();

    public static final String ACTION_SCHEDULE_START = "ul.fcul.lasige.findvictim.action.ALARM_SCHEDULE_START";
    public static final String ACTION_SCHEDULE_STOP = "ul.fcul.lasige.findvictim.action.ALARM_SCHEDULE_STOP";
    public static final String EXTRA_ALERT_ID = "alert_id";

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
        if (sInstance == null) sInstance = new GcmScheduler(context);
        return sInstance;
    }

    public void scheduleAlarm(Context context, Alert alert) {
        WebLogging.logMessage(context, "scheduled alert", DeviceUtils.getWifiMacAddress(), "FindVictim");

        // schedule start alarm
        Intent startIntent = new Intent(context, GcmSchedulerReceiver.class);
        startIntent.setAction(ACTION_SCHEDULE_START);
        startIntent.putExtra(EXTRA_ALERT_ID, alert.getAlertID());

        mStartSensorsIntent = PendingIntent.getBroadcast(context, 0,
                startIntent, PendingIntent.FLAG_UPDATE_CURRENT); // TODO alarmmanager only guarantees one start alarm

        // get starting time
        Date date = alert.getDate();
        if (date != null) {
            mAlarmManager.set(AlarmManager.RTC_WAKEUP, date.getTime(), mStartSensorsIntent);
            Log.d(TAG, "Alert scheduled to start at " + date.toString());
        }

        // schedule stop alarm
        Intent stopIntent = new Intent(context, GcmSchedulerReceiver.class);
        stopIntent.setAction(ACTION_SCHEDULE_STOP);
        stopIntent.putExtra(EXTRA_ALERT_ID, alert.getAlertID());

        mStopSensorsIntent = PendingIntent.getBroadcast(context, 1234,
                stopIntent, PendingIntent.FLAG_UPDATE_CURRENT); // TODO alarmmanager only guarantees one stop alarm

        // get stopping time
        long duration = alert.getDuration();
        if (duration != -1) {
            mAlarmManager.set(AlarmManager.RTC_WAKEUP, date.getTime() + duration, mStopSensorsIntent);
            Log.d(TAG, "Alert scheduled to stop at " + new Date(date.getTime() + duration).toString());
        }


    }

    private void alertNotification(Context context, Alert alert, Alert.DANGER danger) {

        // Creates an explicit intent for an Activity in your app
        Intent resultIntent = new Intent(context, AlertActivity.class);
        resultIntent.putExtra("Alert", alert);

        NotificationCompat.Builder mBuilder =null;
        switch (danger){
            case IN_LOCATION:
                mBuilder = new NotificationCompat.Builder(context)
                        .setSmallIcon(R.drawable.warning_notification)
                        .setContentTitle(alert.getName()+" at " +alert.getDate().getHours() + ":" +alert.getDate().getMinutes()  )
                        .setContentText(alert.getDescription());
                resultIntent.putExtra("knownLocation",true);
                resultIntent.putExtra("isInside",true);
                break;
            case UNKNOWN:
                mBuilder = new NotificationCompat.Builder(context)
                        .setSmallIcon(R.drawable.warning_notification_y)
                        .setContentTitle(alert.getName()+" at " +alert.getDate().getHours() + ":" +alert.getDate().getMinutes()  )
                        .setContentText(alert.getDescription()).setOngoing(true);
                resultIntent.putExtra("knownLocation",false);
                break;
            case NOT_IN_LOCATION:
                mBuilder = new NotificationCompat.Builder(context)
                        .setSmallIcon(R.drawable.warning_notification_g)
                        .setContentTitle(alert.getName()+" at " +alert.getDate().getHours() + ":" +alert.getDate().getMinutes()  )
                        .setContentText(alert.getDescription());
                resultIntent.putExtra("knownLocation",true);
                resultIntent.putExtra("isInside",false);

                break;
        }


        // The stack builder object will contain an artificial back stack for the
        // started Activity.
        // This ensures that navigating backward from the Activity leads out of
        // your application to the Home screen.
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(context);
        // Adds the back stack for the Intent (but not the Intent itself)
        stackBuilder.addParentStack(AlertActivity.class);
        // Adds the Intent that starts the Activity to the top of the stack
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent =
                stackBuilder.getPendingIntent(
                        0,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );
        mBuilder.setContentIntent(resultPendingIntent);
        NotificationManager mNotificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        mNotificationManager.notify(1, mBuilder.build());
    }

    public void cancelAlarm(Context context, int alertID) {
        if (Alert.Store.updateAlertStatus(DatabaseHelper.getInstance(context).getWritableDatabase(),
                alertID, Alert.STATUS.STOPPED)) {

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
            //TODO create onStop
            RegisterInFind.sharedInstance(context).stopAlert(alertID);
           /* VictimApp app = (VictimApp) context.getApplicationContext();
            app.stopSensors();*/
        }
    }

    private HandlerThread mHandlerThread; // thread with queue and looper
    LocationSensor locationSensor;
    public void receivedAlert(final Context applicationContext, final Alert alert) {
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        Handler mStateHandler = new Handler(mHandlerThread.getLooper());
       mStateHandler.post(new Runnable(){
           @Override
           public void run() {
                 locationSensor = new LocationSensor(applicationContext);

               locationSensor.startSensor();
           }
           });

        mStateHandler.postDelayed(new Runnable() {
            @Override
            public void run() {

                Location location = (Location) locationSensor.getCurrentValue();
                double lat=location.getLatitude();
                Log.d(TAG, "latitude:"+ lat);

                if(lat==0){
                    Log.d(TAG, "Unknown");

                    alertNotification(applicationContext, alert, Alert.DANGER.UNKNOWN);
                    //prompt alert activity
                }else {
                    if (PositionUtils.isInLocation(location.getLatitude(), location.getLongitude(), alert.getLatStart(),
                            alert.getLonStart(), alert.getLatEnd(), alert.getLonEnd())) {
                        Log.d(TAG, "In location ");

                        alertNotification(applicationContext, alert, Alert.DANGER.IN_LOCATION);
                        scheduleAlarm(applicationContext,alert);
                        //TODO Download maps and routes
                    } else {
                        Log.d(TAG, "Not In location ");
                        alertNotification(applicationContext, alert, Alert.DANGER.NOT_IN_LOCATION);
                    }
                }
                locationSensor.stopSensor();
                if (mHandlerThread!= null) {
                    Thread moribund = mHandlerThread;
                    mHandlerThread = null;
                    moribund.interrupt();
                }
            }


        }, 30000);

    }


    public static class GcmSchedulerReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Received alarm");

            if (intent != null) {
                String action = intent.getAction();
                int alertID= intent.getIntExtra(EXTRA_ALERT_ID,0);
                if (action != null && action.equalsIgnoreCase(ACTION_SCHEDULE_START)) {
                    Log.d(TAG, "Received alarm to start sensors");

                    // update alert status
                    Alert.Store.updateAlertStatus(DatabaseHelper.getInstance(context).getWritableDatabase(),
                            alertID, Alert.STATUS.ONGOING);

                    // start sensors service
                   //TODO ON RECEIVE ALERT START
                    RegisterInFind.sharedInstance(context).startAlert(alertID);
                   /* VictimApp app = (VictimApp) context.getApplicationContext();
                    app.starSensors();*/

                } else if (action != null && action.equalsIgnoreCase((ACTION_SCHEDULE_STOP))) {
                    Log.d(TAG, "Received alarm to stop sensors");

                    // update alert status
                    Alert.Store.updateAlertStatus(DatabaseHelper.getInstance(context).getWritableDatabase(),
                            alertID, Alert.STATUS.STOPPED);

                    // stop sensors service
                    //TODO ONRECEIVE ALERT STOP
                    RegisterInFind.sharedInstance(context).stopAlert(alertID);

                   /* VictimApp app = (VictimApp) context.getApplicationContext();
                    app.stopSensors();*/
                }
            }
        }
    }
}
