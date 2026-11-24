package com.college.attendance.mobile.model;

/**
 * Request body for {@code POST /api/face/active-liveness/sessions}.
 * Mirrors {@code ActiveLivenessStartRequest} from the Spring backend.
 *
 * Rules enforced by Spring:
 *   - purpose == "VERIFICATION"  → attemptId must be non-null/non-blank.
 *   - purpose == "REGISTRATION"  → attemptId must be null.
 */
public class ActiveLivenessStartRequest {
    public String purpose;
    public String attemptId;

    public ActiveLivenessStartRequest(String purpose, String attemptId) {
        this.purpose   = purpose;
        this.attemptId = attemptId;
    }

    public static ActiveLivenessStartRequest forVerification(String attemptId) {
        return new ActiveLivenessStartRequest("VERIFICATION", attemptId);
    }

    public static ActiveLivenessStartRequest forRegistration() {
        return new ActiveLivenessStartRequest("REGISTRATION", null);
    }
}
