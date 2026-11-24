package com.college.attendance.mobile.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * JVM unit tests for {@link ActiveLivenessSession} DTO.
 * Tests DTO field mapping, state helpers, and edge cases.
 * No Android framework dependency — runs as a plain JUnit test.
 */
public class ActiveLivenessSessionTest {

    // -----------------------------------------------------------------------
    // State helper methods
    // -----------------------------------------------------------------------

    @Test
    public void isActive_returnsTrueForActiveState() {
        ActiveLivenessSession s = session("ACTIVE");
        assertTrue(s.isActive());
        assertFalse(s.isTerminal());
    }

    @Test
    public void isCompleted_returnsTrueForCompletedState() {
        ActiveLivenessSession s = session("COMPLETED");
        assertTrue(s.isCompleted());
        assertTrue(s.isTerminal());
    }

    @Test
    public void isFailed_returnsTrueForFailedState() {
        ActiveLivenessSession s = session("FAILED");
        assertTrue(s.isFailed());
        assertTrue(s.isTerminal());
    }

    @Test
    public void isCancelled_returnsTrueForCancelledState() {
        ActiveLivenessSession s = session("CANCELLED");
        assertTrue(s.isCancelled());
        assertTrue(s.isTerminal());
    }

    @Test
    public void isExpired_returnsTrueForExpiredState() {
        ActiveLivenessSession s = session("EXPIRED");
        assertTrue(s.isExpired());
        assertTrue(s.isTerminal());
    }

    @Test
    public void isProcessing_returnsTrueForProcessingState() {
        ActiveLivenessSession s = session("PROCESSING");
        assertTrue(s.isProcessing());
        assertFalse(s.isTerminal());
    }

    @Test
    public void isTerminal_falseForActiveAndProcessing() {
        assertFalse(session("ACTIVE").isTerminal());
        assertFalse(session("PROCESSING").isTerminal());
    }

    // -----------------------------------------------------------------------
    // Field mapping
    // -----------------------------------------------------------------------

    @Test
    public void fieldsAssignedCorrectly() {
        ActiveLivenessSession s = new ActiveLivenessSession();
        s.sessionId = "uuid-1234";
        s.purpose = "VERIFICATION";
        s.state = "ACTIVE";
        s.challenge = Arrays.asList("BLINK", "TURN_LEFT");
        s.expiresAt = "2026-11-24T15:00:00Z";
        s.retryCount = 0;
        s.nextExpectedSequence = 3L;
        s.reason = null;

        assertEquals("uuid-1234", s.sessionId);
        assertEquals("VERIFICATION", s.purpose);
        assertEquals("ACTIVE", s.state);
        assertEquals(2, s.challenge.size());
        assertEquals("BLINK", s.challenge.get(0));
        assertEquals("TURN_LEFT", s.challenge.get(1));
        assertEquals(3L, s.nextExpectedSequence);
        assertNull(s.reason);
    }

    @Test
    public void sessionWithReason_failedState() {
        ActiveLivenessSession s = new ActiveLivenessSession();
        s.state = "FAILED";
        s.reason = "LIVENESS_FAILED";

        assertTrue(s.isFailed());
        assertEquals("LIVENESS_FAILED", s.reason);
    }

    @Test
    public void emptyChallenge_doesNotThrow() {
        ActiveLivenessSession s = new ActiveLivenessSession();
        s.challenge = Collections.emptyList();
        assertNotNull(s.challenge);
        assertTrue(s.challenge.isEmpty());
    }

    // -----------------------------------------------------------------------
    // StartRequest factories
    // -----------------------------------------------------------------------

    @Test
    public void forVerification_setsCorrectFields() {
        ActiveLivenessStartRequest r = ActiveLivenessStartRequest.forVerification("attempt-99");
        assertEquals("VERIFICATION", r.purpose);
        assertEquals("attempt-99", r.attemptId);
    }

    @Test
    public void forRegistration_hasNullAttemptId() {
        ActiveLivenessStartRequest r = ActiveLivenessStartRequest.forRegistration();
        assertEquals("REGISTRATION", r.purpose);
        assertNull(r.attemptId);
    }

    @Test
    public void startRequestConstructor_setsFields() {
        ActiveLivenessStartRequest r = new ActiveLivenessStartRequest("VERIFICATION", "att-1");
        assertEquals("VERIFICATION", r.purpose);
        assertEquals("att-1", r.attemptId);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ActiveLivenessSession session(String state) {
        ActiveLivenessSession s = new ActiveLivenessSession();
        s.state = state;
        return s;
    }
}
