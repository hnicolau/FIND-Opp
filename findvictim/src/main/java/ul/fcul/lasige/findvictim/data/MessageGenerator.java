package ul.fcul.lasige.findvictim.data;

import android.content.Context;
import android.location.Location;

import ul.fcul.lasige.findvictim.sensors.SensorManager;
import ul.fcul.lasige.findvictim.sensors.SensorsService;

/**
 * Created by hugonicolau on 26/11/15.
 */
public class MessageGenerator {

    public static class GenerateMessageTask implements Runnable {
        private static final String TAG = GenerateMessageTask.class.getSimpleName();

        private final SensorManager mSensorsManager;
        private final SensorsService mSensorService;
        private final Context mContext;

        public GenerateMessageTask(Context context, SensorManager sensormanager, SensorsService sensorsService) {
            super();
            mSensorsManager = sensormanager;
            mSensorService = sensorsService;
            mContext = context;
        }

        @Override
        public void run() {
            // generate message

            // get sensor value
            Integer batteryLevel = (Integer) mSensorsManager.getSensorCurrentValue(SensorManager.SensorType.Battery);
            Location location = (Location) mSensorsManager.getSensorCurrentValue(SensorManager.SensorType.Location);

            // build message
            Message message = new Message();
            message.OriginMac = TokenStore.getMacAddress(mContext);
            message.GoogleAccount = TokenStore.getGoogleAccount(mContext);
            message.TimeSent = System.currentTimeMillis();
            message.TimeReceived = -1;
            message.Battery = batteryLevel;
            if(location != null) {
                message.LocationLatitude = location.getLatitude();
                message.LocationLongitude = location.getLongitude();
                message.LocationAccuracy = location.getAccuracy();
                message.LocationTimestamp = location.getTime();
            }

            // send message
            mSensorService.sendMessage(message);
        }
    }
}
