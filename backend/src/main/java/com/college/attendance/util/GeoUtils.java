package com.college.attendance.util;

/** Pure geo maths, no Spring dependency so it is easy to unit test. */
public final class GeoUtils {

    private static final double EARTH_RADIUS_M = 6_371_008.8; // mean Earth radius

    private GeoUtils() {}

    /** Great-circle distance between two lat/lng points in metres (haversine). */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);

        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static boolean isValidCoordinate(Double lat, Double lng) {
        return lat != null && lng != null
                && !lat.isNaN() && !lng.isNaN()
                && lat >= -90 && lat <= 90
                && lng >= -180 && lng <= 180;
    }
}
