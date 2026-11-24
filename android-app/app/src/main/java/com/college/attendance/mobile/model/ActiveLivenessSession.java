package com.college.attendance.mobile.model;

import java.util.List;

/**
 * Mirrors {@code ActiveLivenessSessionResponse} from the Spring backend.
 * Field names match the JSON keys emitted by the Spring record exactly.
 */
public class ActiveLivenessSession {
    /** Opaque UUID assigned by Spring. */
    public String sessionId;

    /** "REGISTRATION" or "VERIFICATION". */
    public String purpose;

    /**
     * Server-authoritative lifecycle state.
     * Values: ACTIVE | PROCESSING | COMPLETED | FAILED | CANCELLED | EXPIRED
     */
    public String state;

    /**
     * Server-generated challenge list. Never modified by Android.
     * Values: "BLINK", "TURN_LEFT", "TURN_RIGHT"
     */
    public List<String> challenge;

    /** ISO-8601 instant at which the session expires (server-clock). */
    public String expiresAt;

    /** Number of server-issued retries consumed so far. */
    public int retryCount;

    /**
     * The sequence number Spring expects on the NEXT frame submission.
     * Android advances its local counter only when Spring confirms acceptance
     * (i.e. when this value increases in a successful frame response).
     */
    public long nextExpectedSequence;

    /**
     * Server-generated reason string for terminal states.
     * Null while ACTIVE/PROCESSING. Examples: "SESSION_EXPIRED", "LIVENESS_FAILED".
     */
    public String reason;

    // -----------------------------------------------------------------------
    // Convenience helpers
    // -----------------------------------------------------------------------

    public boolean isActive()     { return "ACTIVE".equals(state); }
    public boolean isProcessing() { return "PROCESSING".equals(state); }
    public boolean isCompleted()  { return "COMPLETED".equals(state); }
    public boolean isFailed()     { return "FAILED".equals(state); }
    public boolean isCancelled()  { return "CANCELLED".equals(state); }
    public boolean isExpired()    { return "EXPIRED".equals(state); }

    /** True if the session is in a terminal state and cannot accept more frames. */
    public boolean isTerminal() {
        return isCompleted() || isFailed() || isCancelled() || isExpired();
    }
}
