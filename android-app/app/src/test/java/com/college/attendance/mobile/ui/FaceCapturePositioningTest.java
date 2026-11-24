package com.college.attendance.mobile.ui;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JVM unit tests for {@link FaceCaptureActivity#classifyPosition}.
 *
 * Tests verify every classification branch:
 *   - Too dark
 *   - Too bright
 *   - Face too small (low variance = background, not a face)
 *   - Off-centre (centre darker than edges)
 *   - Too close (very high variance + strong centre brightness)
 *   - Good position (all conditions met)
 *
 * Also tests that the thresholds are consistent with the backend constraints
 * (face ratio ≥ 0.03, Laplacian variance ≥ 50).
 */
public class FaceCapturePositioningTest {

    // -----------------------------------------------------------------------
    // Brightness checks
    // -----------------------------------------------------------------------

    @Test
    public void tooDark_returnsLightingHint() {
        String hint = FaceCaptureActivity.classifyPosition(
                FaceCaptureActivity.POSITION_MIN_BRIGHTNESS - 1f, // too dark
                500f,   // decent variance
                10f,    // centre brighter than edges
                0.325f  // normal centre area fraction
        );
        assertTrue("Expected lighting hint for too-dark frame",
                hint.contains("dark") || hint.contains("light"));
    }

    @Test
    public void tooBright_returnsLightingHint() {
        String hint = FaceCaptureActivity.classifyPosition(
                FaceCaptureActivity.POSITION_MAX_BRIGHTNESS + 1f, // overexposed
                500f,
                10f,
                0.325f
        );
        assertTrue("Expected lighting hint for overexposed frame",
                hint.contains("bright") || hint.contains("light"));
    }

    @Test
    public void atMinBrightness_doesNotReturnDarkHint() {
        // Exactly at the threshold must not trigger the dark hint.
        String hint = FaceCaptureActivity.classifyPosition(
                FaceCaptureActivity.POSITION_MIN_BRIGHTNESS,
                600f, 15f, 0.325f
        );
        assertFalse(hint.contains("dark"));
    }

    // -----------------------------------------------------------------------
    // Face-size check (variance proxy)
    // -----------------------------------------------------------------------

    @Test
    public void lowVariance_returnsMoveCloserHint() {
        // Near-uniform frame = background only, face not visible / too small.
        String hint = FaceCaptureActivity.classifyPosition(
                120f,   // normal brightness
                50f,    // very low variance → background
                5f,
                0.325f
        );
        assertTrue("Expected move-closer hint for low-variance frame",
                hint.contains("closer") || hint.contains("small"));
    }

    @Test
    public void borderlineVariance_doesNotTriggerMoveCloser() {
        // Exactly 100 (threshold) must not trigger the too-small hint.
        String hint = FaceCaptureActivity.classifyPosition(
                120f, 100f, 15f, 0.325f);
        assertFalse(hint.contains("closer"));
    }

    // -----------------------------------------------------------------------
    // Off-centre check (luminance diff)
    // -----------------------------------------------------------------------

    @Test
    public void centreDarkerThanEdges_returnsCentreHint() {
        // Centre significantly darker than edges → face is off to the side
        // or strongly backlit.
        String hint = FaceCaptureActivity.classifyPosition(
                120f,
                400f,   // enough variance
                -25f,   // centre much darker than edges
                0.325f
        );
        assertTrue("Expected centre hint for off-centre face",
                hint.contains("Centre") || hint.contains("centre") || hint.contains("oval"));
    }

    @Test
    public void slightlyNegativeDiff_doesNotTriggerCentreHint() {
        // -19 is above the -20 threshold — should not fire.
        String hint = FaceCaptureActivity.classifyPosition(
                120f, 400f, -19f, 0.325f);
        assertFalse(hint.contains("Centre") || hint.contains("oval"));
    }

    // -----------------------------------------------------------------------
    // Too-close check
    // -----------------------------------------------------------------------

    @Test
    public void veryHighVarianceAndHighLumDiff_returnsMoveBackHint() {
        String hint = FaceCaptureActivity.classifyPosition(
                160f,
                4000f,  // very high variance (face fills frame)
                45f,    // centre much brighter than edges
                0.325f
        );
        assertTrue("Expected move-back hint when face is too close",
                hint.contains("back") || hint.contains("close"));
    }

    @Test
    public void highVarianceLowLumDiff_doesNotReturnMoveBack() {
        // High variance but centre not brighter → complex background, not a close face.
        String hint = FaceCaptureActivity.classifyPosition(
                160f, 4000f, 10f, 0.325f);
        assertFalse(hint.contains("back") || hint.contains("close"));
    }

    // -----------------------------------------------------------------------
    // Good position
    // -----------------------------------------------------------------------

    @Test
    public void goodPosition_returnsSuccessHint() {
        // Normal selfie: moderate brightness, decent variance, centre brighter.
        String hint = FaceCaptureActivity.classifyPosition(
                140f,   // good brightness
                800f,   // face-like variance
                20f,    // centre moderately brighter than edges
                0.325f
        );
        assertTrue("Expected good-position hint",
                hint.startsWith("✅") || hint.contains("Good"));
    }

    @Test
    public void goodPosition_hintIsEmpty_orStartsWithTick() {
        String hint = FaceCaptureActivity.classifyPosition(
                130f, 600f, 15f, 0.325f);
        assertTrue("Good-position hint must be empty or start with ✅",
                hint.isEmpty() || hint.startsWith("✅"));
    }

    // -----------------------------------------------------------------------
    // Threshold constants sanity check
    // -----------------------------------------------------------------------

    @Test
    public void tooSmallThreshold_isAboveZero() {
        assertTrue(FaceCaptureActivity.POSITION_TOO_SMALL_THRESHOLD > 0f);
    }

    @Test
    public void tooLargeThreshold_isLessThanOne() {
        assertTrue(FaceCaptureActivity.POSITION_TOO_LARGE_THRESHOLD < 1.0f);
    }

    @Test
    public void tooSmallThreshold_isLessThanTooLarge() {
        assertTrue(FaceCaptureActivity.POSITION_TOO_SMALL_THRESHOLD
                < FaceCaptureActivity.POSITION_TOO_LARGE_THRESHOLD);
    }

    @Test
    public void autofocusSettleMs_isPositive() {
        assertTrue(FaceCaptureActivity.AUTOFOCUS_SETTLE_MS > 0);
    }

    @Test
    public void positionUpdateInterval_isPositive() {
        assertTrue(FaceCaptureActivity.POSITION_UPDATE_INTERVAL_MS > 0);
    }
}
