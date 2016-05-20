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
import java.util.LinkedHashSet;
import java.util.Map.Entry;
import java.util.Set;

import ul.fcul.lasige.find.beaconing.BeaconingManager;
import ul.fcul.lasige.find.data.DbController;
import ul.fcul.lasige.find.lib.data.Packet;
import ul.fcul.lasige.find.packetcomm.PacketDownloadService;
import ul.fcul.lasige.find.packetcomm.PacketRegistry;
import ul.fcul.lasige.find.packetcomm.PacketSenderService;

/**
 * Created by unzi on 17/02/2016.
 */
public class SynchronizedPackets {

    public static final String TAG = SynchronizedPackets.class.getSimpleName();

    public static String LAST_CONNECTION = "lastConnection";
    public static String LAST_DOWNLOAD_CONNECTION = "lastDownloadConnection";


    public static void syncPackets(final Context context) {
        uploadPackets( context);
        downloadPackets(context);
    }

    private static void downloadPackets(Context context) {
        DbController dbController = new DbController(context);
        //save what time this sync occured to update the last connection timestamp in case it is successful
        long newSyncTime = System.currentTimeMillis() / 1000;

        //get last time sync with internet
        SharedPreferences sharedPref = context.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        long lastConnection = sharedPref.getLong(LAST_CONNECTION, 0);
        Set<String> downloadList = new LinkedHashSet<String>();
        ArrayList<byte[]> protocolHashes = new ArrayList<byte[]>();
        long ttl =3600;
        Cursor protocols = dbController.getProtocols();

        while (protocols.moveToNext()) {

            if (downloadList.add(protocols.getString(5))) {
                Log.d(TAG, "downloading packets from" + protocols.getString(5));
                ttl=  protocols.getLong(7);
                protocolHashes.add(protocols.getBlob(2));

            }
        }
        protocols.close();

        int index = 0;
        for (String downloadEndpoint : downloadList) {
            BeaconingManager.getInstance(context).acquireInternetLock(downloadEndpoint);
            PacketDownloadService.startGetPacketInternet(context, downloadEndpoint, newSyncTime, protocolHashes.get(index),ttl);
            index++;
        }

    }

    private static boolean uploadPackets(Context context) {
        DbController dbController = new DbController(context);
        //save what time this sync occured to update the last connection timestamp in case it is successful
        long newSyncTime = System.currentTimeMillis() / 1000;

        //get last time sync with internet
        SharedPreferences sharedPref = context.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        long lastConnection = sharedPref.getLong(LAST_CONNECTION, 0);


        //get all packets since last connection
        //TODO  remove packets not synchronized
        Cursor cursor = dbController.getAllPackets(lastConnection);

        HashMap<String, JSONArray> packetsToSync = new HashMap<>();


        JSONArray packetsToEndpoint;
        String endpoint;
        String protocolName;
        while (cursor.moveToNext()) {
            long packetId = Packet.fromCursor(cursor).getPacketId();
            byte[] hashProtocol = Packet.fromCursor(cursor).getPacketProtocol();
            byte[] packectData = Packet.fromCursor(cursor).getData();
            Cursor protocol = dbController.getProtocolEndpointByHash(hashProtocol);



            if ((endpoint = protocol.getString(4)) != null) {
                protocolName = protocol.getString(1);
                Log.d(TAG, "sending packets to: " + endpoint);
                Log.d(TAG, "packet id: " + packetId);
                Log.d(TAG, "hashProtocol: " + (hashProtocol != null));

                if (packetsToSync.containsKey(endpoint)) {
                    packetsToEndpoint = packetsToSync.get(endpoint);
                } else {
                    packetsToEndpoint = new JSONArray();
                }

                JSONObject packetJSONWrapper = createJSONByteData(Base64.encodeToString(packectData, Base64.DEFAULT), protocolName);
                packetsToEndpoint.put(packetJSONWrapper);

                packetsToSync.put(endpoint, packetsToEndpoint);
            }
            protocol.close();

        }

        for (Entry<String, JSONArray> entry : packetsToSync.entrySet()) {
            endpoint = entry.getKey();
            packetsToEndpoint = entry.getValue();
            BeaconingManager.getInstance(context).acquireInternetLock(endpoint);
            PacketSenderService.startSendPacketInternet(context, endpoint, packetsToEndpoint, newSyncTime);
        }
        cursor.close();

        return true;
    }


    public static JSONObject createJSONByteData(String byteData, String protocol) {
        JSONObject json = new JSONObject();

        try {
            json.put("data", byteData);
            json.put("protocol", protocol);

        } catch (JSONException e) {
            return null;
        }
        return json;
    }

    public static void packetsSuccessfullySync(final Context context, long newSyncTime) {
        //TODO have the time of the * per protocol instead of 1
        SharedPreferences sharedPref = context.getSharedPreferences(SynchronizedPackets.TAG, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putLong(SynchronizedPackets.LAST_CONNECTION, newSyncTime);
        editor.commit();

        DbController dbController = new DbController(context);
        dbController.updateSyncPackets(newSyncTime);
    }

    public static long lastDownloadedSuccess(Context context) {
        //get last time sync with internet
        SharedPreferences sharedPref = context.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        return sharedPref.getLong(LAST_DOWNLOAD_CONNECTION, 0);
    }

    public static void packetsSuccessfullyDownloaded(final Context context, long newSyncTime) {
        //TODO have the time of the * per protocol instead of 1

        SharedPreferences sharedPref = context.getSharedPreferences(SynchronizedPackets.TAG, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putLong(SynchronizedPackets.LAST_DOWNLOAD_CONNECTION, newSyncTime);
        editor.commit();
    }
}
