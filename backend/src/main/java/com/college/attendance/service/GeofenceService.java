package com.college.attendance.service;

import com.college.attendance.util.GeoUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Checks that a student is physically inside the ICT building zone
 * (a circle: centre + radius) when marking attendance.
 *
 * All values live in application.properties under "geofence.*".
 */
@Service
public class GeofenceService {

    /** Outcome of a check. distanceMeters is null when no valid location was supplied. */
    public record Result(boolean allowed, String message, Double distanceMeters) {}

    @Value("${geofence.enabled:true}")
    private boolean enabled;

    @Value("${geofence.zone-name:ICT Building}")
    private String zoneName;

    @Value("${geofence.center-lat:0}")
    private double centerLat;

    @Value("${geofence.center-lng:0}")
    private double centerLng;

    @Value("${geofence.radius-meters:100}")
    private double radiusMeters;

    /** Reject readings whose reported GPS error is worse than this. */
    @Value("${geofence.max-accuracy-meters:50}")
    private double maxAccuracyMeters;

    @PostConstruct
    void validateConfig() {
        if (!enabled) {
            return;
        }
        if (!GeoUtils.isValidCoordinate(centerLat, centerLng) || (centerLat == 0 && centerLng == 0)) {
            throw new IllegalStateException(
                    "Geofencing is enabled but geofence.center-lat / geofence.center-lng are not set "
                            + "in application.properties. Set the ICT building coordinates "
                            + "(or set geofence.enabled=false to turn the check off).");
        }
        if (radiusMeters <= 0 || maxAccuracyMeters <= 0) {
            throw new IllegalStateException(
                    "geofence.radius-meters and geofence.max-accuracy-meters must be positive.");
        }
    }

    public Result check(Double lat, Double lng, Double accuracy, Boolean mockLocation) {

        if (!enabled) {
            return new Result(true, "Geofence disabled", null);
        }

        if (!GeoUtils.isValidCoordinate(lat, lng) || accuracy == null || accuracy.isNaN() || accuracy < 0) {
            return new Result(false,
                    "Location is required to mark attendance. Turn on GPS and allow location access.",
                    null);
        }

        if (Boolean.TRUE.equals(mockLocation)) {
            return new Result(false,
                    "Mock/fake location detected. Disable it and try again.", null);
        }

        double distance = GeoUtils.distanceMeters(lat, lng, centerLat, centerLng);

        if (accuracy > maxAccuracyMeters) {
            return new Result(false,
                    "GPS signal is too weak (accuracy ±" + Math.round(accuracy) + " m, need ±"
                            + Math.round(maxAccuracyMeters) + " m or better). "
                            + "Move near a window or outside and try again.",
                    distance);
        }

        if (distance > radiusMeters) {
            return new Result(false,
                    "You are about " + Math.round(distance) + " m from the " + zoneName
                            + ". Attendance can only be marked within "
                            + Math.round(radiusMeters) + " m.",
                    distance);
        }

        return new Result(true, "Inside " + zoneName, distance);
    }
}
