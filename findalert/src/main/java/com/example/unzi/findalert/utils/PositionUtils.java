package com.example.unzi.findalert.utils;

import android.location.Location;

/**
 * Created by hugonicolau on 04/12/15.
 */
public class PositionUtils {
    private static final String TAG = PositionUtils.class.getSimpleName();

    public static boolean isInLocation(Location loc, double f_latS, double f_lonS, double f_latE, double f_lonE) {
        double lat = loc.getLatitude();
        double lon = loc.getLongitude();
        return isInLocation(lat, lon, f_latS, f_lonS, f_latE, f_lonE);
    }

    public static boolean isInLocation(double lat, double lon, double lat1, double lon1, double lat2, double lon2) {
        return lat < lat1 && lat > lat2 && lon > lon2 && lon < lon1;
    }
}
