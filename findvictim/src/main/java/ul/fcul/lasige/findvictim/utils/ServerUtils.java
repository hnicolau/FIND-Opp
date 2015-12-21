package ul.fcul.lasige.findvictim.utils;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import ul.fcul.lasige.findvictim.data.Message;

/**
 * Created by hugonicolau on 07/12/15.
 */
public class ServerUtils {

    public static JSONObject buildJSONFromMessage(Context context, Message message) {
        JSONObject json = new JSONObject();

        try {
            json.put("nodeid", message.OriginMac);
            json.put("timestamp", message.TimeSent);
            json.put("latitude", message.LocationLatitude);
            json.put("longitude", message.LocationLongitude);
            json.put("accuracy", message.LocationAccuracy);
            json.put("locationTimestamp", message.LocationTimestamp);
            json.put("battery", message.Battery);
            json.put("steps", 0); // TODO
            json.put("screen", 0); // TODO
            json.put("safe", 0); // TODO
            json.put("msg", ""); // TODO
            /*json.put("status", ""); // deprecated
            json.put("statusTimestamp", 0); // deprecated
            json.put("origin", message.OriginMac);
            json.put("target", ""); // deprecated
            json.put("targetLatitude", 0); // deprecated
            json.put("targetLongitude", 0); // deprecated
            json.put("targetRadius", 0); // deprecated*/


        } catch(JSONException e) {
            return null;
        }
        return json;
    }
}
