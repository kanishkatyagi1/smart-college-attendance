package com.college.attendance.mobile.util;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

/**
 * Gets one fresh, high-accuracy GPS fix for the geofence check.
 * The server does the real validation; this only collects the reading.
 */
public class LocationHelper {

    public interface Callback {
        void onLocation(double latitude, double longitude, double accuracyMeters, boolean mock);
        void onError(String message);
    }

    public static boolean hasPermission(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isGpsOrNetworkEnabled(Context c) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        return lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    @SuppressLint("MissingPermission") // checked via hasPermission() below
    public static void fetch(Context c, Callback cb) {

        if (!hasPermission(c)) {
            cb.onError("Location permission is required to mark attendance.");
            return;
        }

        if (!isGpsOrNetworkEnabled(c)) {
            cb.onError("Location is turned off. Turn on GPS and try again.");
            return;
        }

        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(c);

        // getCurrentLocation forces a fresh fix instead of returning a stale cached one
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, new CancellationTokenSource().getToken())
                .addOnSuccessListener(loc -> {
                    if (loc == null) {
                        cb.onError("Could not get your location. Move near a window or outside and retry.");
                        return;
                    }
                    cb.onLocation(loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(), isMock(loc));
                })
                .addOnFailureListener(e -> cb.onError("Location error: " + e.getMessage()));
    }

    @SuppressWarnings("deprecation")
    private static boolean isMock(Location loc) {
        return Build.VERSION.SDK_INT >= 31 ? loc.isMock() : loc.isFromMockProvider();
    }
}
