package ul.fcul.lasige.findvictim.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Created by hugonicolau on 26/11/15.
 */
public class Message implements Serializable{
    private static final String TAG = Message.class.getSimpleName();

    /**
     * Generated class serial number
     */
    private static final long serialVersionUID = 4793280315313094725L;

    // id
    public String OriginMac;
    public String GoogleAccount;
    public long TimeSent;
    public long TimeReceived;
    // hash is used to identify replicated messages
    private String mHash;
    // battery
    public int Battery;
    // location
    public double LocationLatitude = Double.NaN;
    public double LocationLongitude = Double.NaN;
    public float LocationAccuracy = Float.NaN;
    public long LocationTimestamp = -1;

    // hash is used as an unique identifier of the message (sender, content, timesent)
    public String getHash() {
        if (mHash == null) {
            String raw = String.format(Locale.US, "%s|%s|%d", OriginMac, GoogleAccount, TimeSent);
            try {
                mHash = createHash(raw);
            } catch (NoSuchAlgorithmException e) {
                mHash = null;
            }
        }
        return mHash;
    }

    public static String createHash(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA1");
        md.update(input.getBytes());
        byte[] digest = md.digest();

        StringBuilder sb = new StringBuilder(2 * digest.length);
        for (byte b : digest) {
            sb.append("0123456789ABCDEF".charAt((b & 0xF0) >> 4));
            sb.append("0123456789ABCDEF".charAt((b & 0x0F)));
        }
        return sb.toString();
    }

    public static byte[] serialize(Message message) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out;
        byte[] buffer = null;

        try {
            out = new ObjectOutputStream(bos);
            out.writeObject(message);
            buffer = bos.toByteArray();
            out.close();
            bos.close();
        } catch (IOException e) {
            Log.e(TAG, "serialize: IOException " + e.getMessage());
        }

        return buffer;
    }

    public static Message deserialize(byte[] message) {
        Message msg = null;
        try {

            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(message));
            msg = (Message) ois.readObject();

        } catch (ClassNotFoundException e) {
            Log.e(TAG, "deserialize: Class not found (different app versions?)", e);
        } catch (IOException e) {
            Log.e(TAG, "deserialize: IOException", e);
        }

        return msg;
    }

    public  JSONObject getJSON() {
        JSONObject json = new JSONObject();

        try {
            json.put("nodeid", OriginMac);
            json.put("timestamp",TimeSent);
            json.put("latitude", LocationLatitude);
            json.put("longitude", LocationLongitude);
            json.put("accuracy", LocationAccuracy);
            json.put("locationTimestamp", LocationTimestamp);
            json.put("battery", Battery);
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

    public static  JSONObject createJSONByteData(String byteData) {
        JSONObject json = new JSONObject();

        try {
            json.put("data", byteData);

        } catch(JSONException e) {
            return null;
        }
        return json;
    }
}
