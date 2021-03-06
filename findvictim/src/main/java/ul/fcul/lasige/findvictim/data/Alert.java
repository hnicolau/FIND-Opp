package ul.fcul.lasige.findvictim.data;

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Build;
import android.provider.BaseColumns;
import android.util.Log;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by hugonicolau on 02/12/15.
 */
public class Alert {
    private static final String TAG = Alert.class.getSimpleName();

    protected String mName;
    protected String mLocation;
    protected String mDate;
    protected String mDuration;
    protected String mLatStart;
    protected String mLonStart;
    protected String mLatEnd;
    protected String mLonEnd;

    public enum STATUS { SCHEDULED, ONGOING, STOPPED };
    private STATUS mStatus;

    public Alert(String name, String location, String date, String duration, String latStart, String lonStart, String latEnd,
                 String lonEnd, STATUS status) {
        mName = name;
        mLocation = location;
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

    public static Alert fromCursor(Cursor data) {
        // check possible null columns
        String duration = "-1";
        if(!data.isNull(data.getColumnIndex(Store.COLUMN_DURATION)))
            duration = data.getString(data.getColumnIndex(Store.COLUMN_DURATION));

        return new Alert(data.getString(data.getColumnIndex(Store.COLUMN_NAME)),
                data.getString(data.getColumnIndex(Store.COLUMN_LOCATION)),
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
        public static final String COLUMN_LOCATION = "location";
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
                        + COLUMN_NAME + " TEXT UNIQUE NOT NULL, "
                        + COLUMN_LOCATION + " TEXT NOT NULL, "
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
                    _ID, COLUMN_NAME, COLUMN_LOCATION, COLUMN_DATE, COLUMN_DURATION, COLUMN_LAT_START, COLUMN_LON_START,
                    COLUMN_LAT_END, COLUMN_LON_END, COLUMN_STATUS};

            return db.query(TABLE_NAME, columns, null, null, null, null, COLUMN_DATE);
        }

        public static Cursor fetchAlerts(SQLiteDatabase db, STATUS status) {
            String[] columns = new String[] {
                    _ID, COLUMN_NAME, COLUMN_LOCATION, COLUMN_DATE, COLUMN_DURATION, COLUMN_LAT_START, COLUMN_LON_START,
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
            values.put(COLUMN_LOCATION, alert.mLocation);
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
        public static boolean updateAlertStatus(SQLiteDatabase db, String name, STATUS status) {

            boolean success = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                success = updateAlertStatus_postSDK11(db, name, status);
            } else {
                success = updateAlertStatus_preSDK11(db, name, status);
            }
            return success;
        }

        @TargetApi(Build.VERSION_CODES.HONEYCOMB)
        private static boolean updateAlertStatus_postSDK11(SQLiteDatabase db, String name, STATUS status) {
            final SQLiteStatement updateStmt = db.compileStatement(
                    "update " + TABLE_NAME + " set "
                            + COLUMN_STATUS + " = ?"
                            + " where " + COLUMN_NAME + " = ?");

            // Bind updated values
            updateStmt.bindString(1, status.name());

            // Bind values for WHERE clause
            updateStmt.bindString(2, name);

            return (updateStmt.executeUpdateDelete() > 0);
        }

        private static boolean updateAlertStatus_preSDK11(SQLiteDatabase db, String name, STATUS status) {
            final StringBuilder updateQueryString =
                    new StringBuilder("update " + TABLE_NAME + " set ")
                            .append(COLUMN_STATUS + " = ")
                            .append(status.name());

            // Add WHERE clause
            updateQueryString.append(" where " + COLUMN_NAME + " = ").append(name);

            db.execSQL(updateQueryString.toString());
            return true;
        }
    }
}
