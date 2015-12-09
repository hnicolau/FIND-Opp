package ul.fcul.lasige.findvictim.sensors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

/**
 * Sensor to obtain device's battery level
 * 
 * @author André Silva <asilva@lasige.di.fc.ul.pt>
 */
public class BatterySensor extends AbstractSensor {
	private static final String TAG = BatterySensor.class.getSimpleName();

	private Integer mBatteryLevel;

	private BroadcastReceiver mBatInfoReceiver = new BroadcastReceiver() {
	    @Override
	    public void onReceive(Context arg0, Intent intent) {
	    	int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
	        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
	    	mBatteryLevel = (int) level / (scale / 100);
			//Log.d(TAG, "Battery level: " + mBatteryLevel);
	    }
	};
	
	/**
	 * Creates a new Battery sensor to receive battery level updates
	 * @param c Android context
	 */
	public BatterySensor(Context c) {
		super(c);
		mBatteryLevel = -1;
	}
	
	@Override
	public void startSensor() {
        Log.d(TAG, "Starting battery sensor");
		IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
		mContext.registerReceiver(mBatInfoReceiver, ifilter);
	}

	@Override
	public Object getCurrentValue() {
		return mBatteryLevel;
	}

	@Override
	public void stopSensor() {
        Log.d(TAG, "Stoping battery sensor");
		mContext.unregisterReceiver(mBatInfoReceiver);
	}

}
