package ul.fcul.lasige.findvictim.sensors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Represents a group of sensors. It allows the storage and
 * retrievel of sensor like a key-value pair. Each key can have 
 * at most one sensor associated. You should use the keys provided 
 * by the {@link SensorType} enumerator.
 * 
 * @author André Silva <asilva@lasige.di.fc.ul.pt>
 */
public class SensorManager {
	
	private HashMap<SensorType, AbstractSensor> mSensors;
	
	/**
	 * Set of keys to be used to access mSensors
	 */
	public enum SensorType {
		
		/**
		 * Battery level sensor
		 */
		Battery,
		
		/**
		 * Victim steps/movement sensor
		 */
		MicroMovements,
		
		/**
		 * User-screen interaction
		 */
		ScreenOn,
		
		/**
		 * Geographical location sensor
		 */
		Location
	}
	
	/**
	 * Creates and initializes a new {@link SensorManager}
	 */
	public SensorManager() {
		mSensors = new HashMap<SensorType, AbstractSensor>();
	}

	/**
	 * Gets the sensor associated with the key
	 * @param type Sensor type
	 * @return sensor instance if exists; null otherwise
	 */
	public AbstractSensor getSensor(SensorType type) {
		return mSensors.get(type);
	}
	
	/**
	 * Gets the sensor value for the selected key
	 * @param type Sensor type
	 * @return value for selected sensor; null if no sensor exists with that key
	 */
	public Object getSensorCurrentValue(SensorType type) {
		AbstractSensor curSensor = getSensor(type);
		if(curSensor == null)
			return null;
		
		return curSensor.getCurrentValue();
	}
	
	/**
	 * Removes a sensor from this group
	 * @param type Sensor type
	 * @param stop If true, the sensor is stopped
	 * @return Removed sensor
	 */
	public AbstractSensor removeSensor(SensorType type, boolean stop) {
		AbstractSensor sensor = mSensors.remove(type);
		if(stop && sensor != null)
			sensor.stopSensor();
		
		return sensor;
	}
	
	/**
	 * Removes all mSensors registered
	 * @param stop If true, all mSensors are stopped
	 * @return List of removed mSensors
	 */
	public List<AbstractSensor> removeAllSensors(boolean stop) {
		List<AbstractSensor> removed = new ArrayList<AbstractSensor>();
		
		for(SensorType k : SensorType.values())
			removed.add(removeSensor(k, stop));
		
		return removed;
	}
	
	/**
	 * Adds a new sensor to this sensor group and starts it 
	 * @param type Sensor type
	 * @param sensor New Sensor to add
	 * @param start Starts the sensor, if successfully registered
	 * @return true if sensor was added; false otherwise (likely there is already a 
	 * sensor associated with that key)
	 */
	public boolean addSensor(SensorType type, AbstractSensor sensor, boolean start) {
		if(mSensors.containsKey(type))
			return false;
		
		mSensors.put(type, sensor);
		
		if(start)
			sensor.startSensor();
		
		return true;
	}

	public void startAllSensors() {
        for(SensorType k : SensorType.values()) {
            AbstractSensor s = getSensor(k);
            if(s != null)
                s.startSensor();
        }
    }

    public void stopAllSensors() {
        for(SensorType k : SensorType.values()) {
            AbstractSensor s = getSensor(k);
            if(s != null)
                s.stopSensor();
        }
    }
	
}
