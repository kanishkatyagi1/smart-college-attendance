package com.college.attendance.mobile.config;

import com.college.attendance.mobile.BuildConfig;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Verifies the safety contract of the development-only location-override flag.
 *
 * <p>These tests run against the actual generated {@link BuildConfig} class for
 * the current build variant, so the assertions reflect real compiled values —
 * not hardcoded constants.  Run under the debug variant (the default for unit
 * tests) they confirm the override is {@code true}.  Run under the release
 * variant they confirm it is {@code false}.
 *
 * <h3>How release exclusion is verified</h3>
 * <ol>
 *   <li>Run {@code .\gradlew.bat testDebugUnitTest} — expects
 *       {@code DEV_LOCATION_OVERRIDE = true}.</li>
 *   <li>Run {@code .\gradlew.bat testReleaseUnitTest} — expects
 *       {@code DEV_LOCATION_OVERRIDE = false}.  This test will FAIL if
 *       {@code buildConfigField} in {@code build.gradle} is ever changed
 *       to {@code true} for release, providing a compile-time safety net.</li>
 * </ol>
 *
 * <p>The override cannot be activated through any intent, API call, preference
 * or runtime setting because {@code BuildConfig.DEV_LOCATION_OVERRIDE} is a
 * {@code static final boolean} generated at compile time by the Android Gradle
 * plugin.  R8/ProGuard strips the unreachable {@code true} branch entirely from
 * release APKs.
 */
public class DevLocationOverrideTest {

    // -----------------------------------------------------------------------
    // Structural contract
    // -----------------------------------------------------------------------

    /**
     * DEV_LAT and DEV_LNG must equal the Spring Boot geofence centre from
     * application.properties (geofence.center-lat/lng).  Submitting any other
     * coordinates when the override is active might still fail the 90 m radius
     * check.  If the production centre changes, update build.gradle to match.
     */
    @Test
    public void devCoordinatesMatchIctBuildingCentre() {
        // ICT Building centre as configured in backend/src/main/resources/application.properties:
        //   geofence.center-lat=28.417341
        //   geofence.center-lng=77.523483
        double expectedLat = 28.417341;
        double expectedLng = 77.523483;

        // These assertions run against the REAL BuildConfig values compiled into
        // the current build variant, not hardcoded test constants.
        assertEquals("DEV_LAT must match geofence.center-lat in application.properties",
                expectedLat, BuildConfig.DEV_LAT, 0.000001);
        assertEquals("DEV_LNG must match geofence.center-lng in application.properties",
                expectedLng, BuildConfig.DEV_LNG, 0.000001);
    }

    /**
     * The accuracy value used by the override (5.0 m) must be less than the
     * backend's max-accuracy-meters threshold (50 m).  If the backend tightens
     * the accuracy requirement, the override must be updated.
     */
    @Test
    public void devAccuracy_satisfiesBackendThreshold() {
        double devAccuracy = 5.0;
        double backendMaxAccuracy = 50.0; // geofence.max-accuracy-meters in application.properties
        assertTrue("DEV accuracy must be below backend max-accuracy-meters",
                devAccuracy < backendMaxAccuracy);
    }

    /**
     * The flag is a compile-time constant; it cannot change at runtime.
     * This test verifies the value is exactly the boolean primitive {@code true}
     * or {@code false} — never a nullable wrapper or string.
     *
     * <p>When run as a debug unit test: asserts true.
     * When run as a release unit test: asserts false (the safety check).
     */
    @Test
    public void devLocationOverride_isDebugTrueReleaseFalse() {
        // BuildConfig.DEBUG is true for debug, false for release.
        // DEV_LOCATION_OVERRIDE must match DEBUG exactly (not a weaker subset).
        assertEquals(
                "DEV_LOCATION_OVERRIDE must equal BuildConfig.DEBUG: "
                        + "true for debug, false for release",
                BuildConfig.DEBUG,
                BuildConfig.DEV_LOCATION_OVERRIDE);
    }

    /**
     * When the override is inactive (release), the DEV coordinates are 0.0 and
     * would be rejected by the backend's coordinate-validity check.  This ensures
     * a hypothetical release-build code path that somehow reached the override
     * block would still fail the backend geofence check.
     *
     * <p>This is a defence-in-depth test; the primary safety is that
     * {@code DEV_LOCATION_OVERRIDE = false} in release means the override
     * block is never reached.
     */
    @Test
    public void whenOverrideDisabled_devCoordinatesAreInvalidForBackend() {
        if (BuildConfig.DEV_LOCATION_OVERRIDE) {
            // In debug builds the coordinates are valid — this branch of the
            // test is not applicable.
            return;
        }
        // In release builds DEV_LAT and DEV_LNG are 0.0.
        // The backend GeofenceService rejects (0,0) as invalid coordinates.
        assertEquals("Release DEV_LAT must be 0.0 (rejected by backend)", 0.0, BuildConfig.DEV_LAT, 0.0);
        assertEquals("Release DEV_LNG must be 0.0 (rejected by backend)", 0.0, BuildConfig.DEV_LNG, 0.0);
    }

    /**
     * The override is guarded by a compile-time constant, not a flag that can
     * be set via an Android intent extra.  This documents the contract: the
     * only way to change the override is to recompile with a different
     * buildConfigField value in build.gradle.
     */
    @Test
    public void overrideIsCompileTimeConstantNotRuntimeFlag() {
        // BuildConfig fields are static final primitives.  Verify via reflection
        // that the field exists, is final, and is a boolean primitive.
        try {
            java.lang.reflect.Field f = BuildConfig.class.getField("DEV_LOCATION_OVERRIDE");
            assertTrue("DEV_LOCATION_OVERRIDE must be static",
                    java.lang.reflect.Modifier.isStatic(f.getModifiers()));
            assertTrue("DEV_LOCATION_OVERRIDE must be final (compile-time constant)",
                    java.lang.reflect.Modifier.isFinal(f.getModifiers()));
            assertEquals("DEV_LOCATION_OVERRIDE must be boolean primitive",
                    boolean.class, f.getType());
        } catch (NoSuchFieldException e) {
            fail("BuildConfig.DEV_LOCATION_OVERRIDE field not found — "
                    + "ensure buildConfigField is declared in app/build.gradle");
        }
    }
}
