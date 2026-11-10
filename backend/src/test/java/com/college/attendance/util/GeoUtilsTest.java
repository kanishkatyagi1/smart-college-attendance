package com.college.attendance.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GeoUtilsTest {

    @Test
    void samePointIsZero() {
        assertEquals(0.0, GeoUtils.distanceMeters(28.4089, 77.3178, 28.4089, 77.3178), 0.001);
    }

    @Test
    void oneDegreeOfLatitudeIsAbout111km() {
        assertEquals(111_195, GeoUtils.distanceMeters(0, 0, 1, 0), 100);
    }

    @Test
    void fiftyMetresNorthIsAboutFiftyMetres() {
        // 50 m ≈ 0.000449° of latitude
        assertEquals(50, GeoUtils.distanceMeters(28.4089, 77.3178, 28.4089 + 0.000449, 77.3178), 0.5);
    }

    @Test
    void validatesCoordinates() {
        assertTrue(GeoUtils.isValidCoordinate(28.4, 77.3));
        assertFalse(GeoUtils.isValidCoordinate(null, 77.3));
        assertFalse(GeoUtils.isValidCoordinate(91.0, 77.3));
        assertFalse(GeoUtils.isValidCoordinate(28.4, 181.0));
        assertFalse(GeoUtils.isValidCoordinate(Double.NaN, 77.3));
    }
}
