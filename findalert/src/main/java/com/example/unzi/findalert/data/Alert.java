package com.example.unzi.findalert.data;

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Build;
import android.provider.BaseColumns;
import android.util.Log;

import java.io.Serializable;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by hugonicolau on 02/12/15.
 */
@SuppressWarnings("serial") //With this annotation we are going to hide compiler warnings
public class Alert implements Serializable {
    private static final String TAG = Alert.class.getSimpleName();

    protected String mName;
    protected int mAlertID;
    protected String mDescription;
    protected String mType;
    protected String mDate;
    protected String mDuration;
    protected String mLatStart;
    protected String mLonStart;
    protected String mLatEnd;
    protected String mLonEnd;

    public enum STATUS { SCHEDULED, ONGOING, STOPPED };
    public enum DANGER { NOT_IN_LOCATION, UNKNOWN, IN_LOCATION };

    private STATUS mStatus;

    public Alert(String name ,String description,String type, int alertID, String date, String duration, String latStart, String lonStart, String latEnd,
                 String lonEnd, STATUS status) {
        mName = name;
        mDescription = description;
        mType = type;
        mAlertID = alertID;
        mDate = date;
        mDuration = duration;
        mLatStart = latStart;
        mLonStart = lonStart;
        mLatEnd = latEnd;
        mLonEnd = lonEnd;
        mStatus = status;
    }

    public String getName() { return mName; }
    public STATUS getStatus() {return mStatus;}
    public void setStatus(STATUS status) {mStatus = status;}
    public String getType() {return mType;}

    public Date getDate() {
        Date dateFor;
        DateFormat formatter = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        try {
            dateFor = formatter.parse(this.mDate);
            return dateFor;
        } catch (ParseException e) {
            e.printStackTrace();
            return null;
        }
    }

    public long getDuration() {
        return Long.valueOf(mDuration) * 1000 * 60;
    }

    public double getLatStart() { return Double.valueOf(mLatStart); }
    public double getLonStart() { return Double.valueOf(mLonStart); }
    public double getLatEnd() { return Double.valueOf(mLatEnd); }
    public double getLonEnd() { return Double.valueOf(mLonEnd); }
    public String getDescription() { return mDescription;  }
    public int getAlertID() { return mAlertID;   }

    public static Alert fromCursor(Cursor data) {
        // check possible null columns
        String duration = "-1";
        if(!data.isNull(data.getColumnIndex(Store.COLUMN_DURATION)))
            duration = data.getString(data.getColumnIndex(Store.COLUMN_DURATION));

        return new Alert(data.getString(data.getColumnIndex(Store.COLUMN_NAME)),
                data.getString(data.getColumnIndex(Store.COLUMN_DESCRIPTION)),
                data.getString(data.getColumnIndex(Store.COLUMN_TYPE)),
                data.getInt(data.getColumnIndex(Store.COLUMN_ALERT_ID)),
                data.getString(data.getColumnIndex(Store.COLUMN_DATE)),
                duration,
                data.getString(data.getColumnIndex(Store.COLUMN_LAT_START)),
                data.getString(data.getColumnIndex(Store.COLUMN_LON_START)),
                data.getString(data.getColumnIndex(Store.COLUMN_LAT_END)),
                data.getString(data.getColumnIndex(Store.COLUMN_LON_END)),
                STATUS.valueOf(data.getString(data.getColumnIndex(Store.COLUMN_STATUS))));
    }

    /*
     * Alert table definition - database
     */
    public static abstract class Store implements BaseColumns {
        public static final String TABLE_NAME = "alerts";

        public static final String COLUMN_NAME = "name";
        public static final String COLUMN_DESCRIPTION = "description";
        public static final String COLUMN_TYPE= "type";
        public static final String COLUMN_ALERT_ID = "alert_id";
        public static final String COLUMN_DATE = "date";
        public static final String COLUMN_DURATION = "duration";
        public static final String COLUMN_LAT_START= "lat_start";
        public static final String COLUMN_LON_START= "lon_start";
        public static final String COLUMN_LAT_END = "lat_end";
        public static final String COLUMN_LON_END= "lon_end";
        public static final String COLUMN_STATUS= "status";

        /* SQL statements */
        public static final String SQL_CREATE_TABLE =
                "CREATE TABLE " + TABLE_NAME + " ("
                        + _ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + COLUMN_NAME + " TEXT NOT NULL, "
                        + COLUMN_DESCRIPTION + " TEXT, "
                        + COLUMN_TYPE + " TEXT, "
                        + COLUMN_ALERT_ID+ " INTEGER, "
                        + COLUMN_DATE + " TEXT NOT NULL, "
                        + COLUMN_DURATION + " TEXT, "
                        + COLUMN_LAT_START + " TEXT NOT NULL, "
                        + COLUMN_LON_START + " TEXT NOT NULL, "
                        + COLUMN_LAT_END + " TEXT NOT NULL, "
                        + COLUMN_LON_END + " TEXT NOT NULL, "
                        + COLUMN_STATUS + " TEXT NOT NULL)";

        /* query methods */
        public static Cursor fetchAllAlerts(SQLiteDatabase db) {
            String[] columns = new String[] {
                    _ID,  COLUMN_NAME,COLUMN_DESCRIPTION,COLUMN_TYPE,COLUMN_ALERT_ID, COLUMN_DATE, COLUMN_DURATION, COLUMN_LAT_START, COLUMN_LON_START,
                    COLUMN_LAT_END, COLUMN_LON_END, COLUMN_STATUS};

            return db.query(TABLE_NAME, columns, null, null, null, null, COLUMN_DATE);
        }

        public static Cursor fetchAlerts(SQLiteDatabase db, STATUS status) {
            String[] columns = new String[] {
                    _ID, COLUMN_NAME, COLUMN_DESCRIPTION,COLUMN_TYPE,COLUMN_ALERT_ID,COLUMN_DATE, COLUMN_DURATION, COLUMN_LAT_START, COLUMN_LON_START,
                    COLUMN_LAT_END, COLUMN_LON_END, COLUMN_STATUS};
            return db.query(TABLE_NAME,
                    columns,
                    COLUMN_STATUS + " = ?",
                    new String[] {status.name()},
                    null,
                    null,
                    COLUMN_DATE);
        }

        /* delete methods */
        public static int discardAllAlerts(SQLiteDatabase db) {
            return db.delete(TABLE_NAME, null, null);
        }

        /* insert methods */
        public static long addAlert(SQLiteDatabase db, Alert alert) {
            ContentValues values = new ContentValues();
            values.put(COLUMN_NAME, alert.mName);
            values.put(COLUMN_DESCRIPTION, alert.mDescription);
            values.put(COLUMN_TYPE, alert.mType);
            values.put(COLUMN_ALERT_ID, alert.mAlertID);
            values.put(COLUMN_DATE, alert.mDate);
            values.put(COLUMN_DURATION, alert.mDuration);
            values.put(COLUMN_LAT_START, alert.mLatStart);
            values.put(COLUMN_LON_START, alert.mLonStart);
            values.put(COLUMN_LAT_END, alert.mLatEnd);
            values.put(COLUMN_LON_END, alert.mLonEnd);
            values.put(COLUMN_STATUS, alert.mStatus.name());

            long alertId = -1;
            try {
                alertId = db.insertOrThrow(TABLE_NAME, null, values);
            } catch (SQLiteConstraintException e) {
                // duplicate
                Log.d(TAG, "Trying to insert a duplicate alert");
            }
            return alertId;
        }

        /* update methods */
        public static boolean updateAlertStatus(SQLiteDatabase db, int alertID, STATUS status) {

            boolean success = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                success = updateAlertStatus_postSDK11(db, alertID, status);
            } else {
                success = updateAlertStatus_preSDK11(db, alertID, status);
            }
            return success;
        }

        @TargetApi(Build.VERSION_CODES.HONEYCOMB)
        private static boolean updateAlertStatus_postSDK11(SQLiteDatabase db, int alertID, STATUS status) {
            final SQLiteStatement updateStmt = db.compileStatement(
                    "update " + TABLE_NAME + " set "
                            + COLUMN_STATUS + " = ?"
                            + " where " + COLUMN_ALERT_ID + " = ?");

            // Bind updated values
            updateStmt.bindString(1, status.name());

            // Bind values for WHERE clause
            updateStmt.bindLong(2, alertID);

            return (updateStmt.executeUpdateDelete() > 0);
        }

        private static boolean updateAlertStatus_preSDK11(SQLiteDatabase db, int alertID, STATUS status) {
            final StringBuilder updateQueryString =
                    new StringBuilder("update " + TABLE_NAME + " set ")
                            .append(COLUMN_STATUS + " = ")
                            .append(status.name());

            // Add WHERE clause
            updateQueryString.append(" where " + COLUMN_ALERT_ID + " = ").append(alertID);

            db.execSQL(updateQueryString.toString());
            return true;
        }
    }
}
