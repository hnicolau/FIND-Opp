package ul.fcul.lasige.find.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;

import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.lib.data.Packet;
import ul.fcul.lasige.find.packetcomm.PacketRegistry;
import ul.fcul.lasige.find.packetcomm.PacketSenderService;

/**
 * Created by unzi on 17/02/2016.
 */
public class SynchronizedPackets   {

    private static final String TAG = SynchronizedPackets.class.getSimpleName();

    private static String LAST_CONNECTION= "lastConnection";

    public static boolean syncPackets(Context context  ){
        DbController dbController = new DbController(context);

        //get last time sync with internet
        SharedPreferences sharedPref = context.getSharedPreferences(TAG,Context.MODE_PRIVATE);
        long lastConnection = sharedPref.getLong(LAST_CONNECTION, 0);

        //get all packets since last connection
        //TODO  remove packets not synchronized
        Cursor cursor = dbController.getAllPackets(lastConnection);

        HashMap<String,JSONArray> packetsToSync = new HashMap<>();
        JSONArray packetsToEndpoint = new JSONArray();
        String endpoint;
        String protocolName;
        while (cursor.moveToNext()) {
            long packetId = Packet.fromCursor(cursor).getPacketId();
            byte [] hashProtocol = Packet.fromCursor(cursor).getPacketProtocol();
            byte [] packectData = Packet.fromCursor(cursor).getData();
            Cursor protocol =dbController.getProtocolEndpointByHash(hashProtocol);
            if(cursor==null)
                return false;
            endpoint = protocol.getString(4);
            protocolName = protocol.getColumnName(1);
            protocol.close();
            Log.d(TAG, "sending packets to: " + endpoint);
            Log.d(TAG, "packet id: " + packetId);
            Log.d(TAG, "hashProtocol: " + (hashProtocol!=null));

            if(packetsToSync.containsKey(endpoint)){
                packetsToEndpoint = packetsToSync.get(endpoint);
            }else{
                packetsToEndpoint = new JSONArray();
            }

            JSONObject packetJSONWrapper = createJSONByteData(Base64.encodeToString(packectData, Base64.DEFAULT),protocolName);
            packetsToEndpoint.put(packetJSONWrapper);

            packetsToSync.put(endpoint, packetsToEndpoint);
        }

        for(Entry<String, JSONArray> entry : packetsToSync.entrySet()) {
            endpoint = entry.getKey();
            packetsToEndpoint  = entry.getValue();
            PacketSenderService.startSendPacketInternet(context, endpoint, packetsToEndpoint);
        }

        return true;
    }


    public static JSONObject createJSONByteData(String byteData, String protocol) {
        JSONObject json = new JSONObject();

        try {
            json.put("data", byteData);
            json.put("protocol", protocol);

        } catch(JSONException e) {
            return null;
        }
        return json;
    }
}
