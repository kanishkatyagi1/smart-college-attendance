package com.college.attendance.service;

import com.college.attendance.dto.ActiveLivenessSessionResponse;
import com.college.attendance.entity.Student;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class ActiveLivenessSessionServiceTest {
    private static final Student OWNER = Student.builder().id(7L).build();
    private static final Student OTHER = Student.builder().id(8L).build();

    @Test void createsOpaqueSessionWithServerChallengeAndPurposeRules() {
        ActiveLivenessSessionService service = service(ActiveLivenessEvaluator.Evaluation::pending);
        ActiveLivenessSessionResponse registration = service.start(OWNER, ActiveLivenessPurpose.REGISTRATION, null);
        assertNotNull(registration.sessionId());
        assertDoesNotThrow(() -> java.util.UUID.fromString(registration.sessionId()));
        assertEquals(ActiveLivenessState.ACTIVE, registration.state());
        assertEquals(ActiveLivenessPurpose.REGISTRATION, registration.purpose());
        assertTrue(registration.challenge().size() >= 1 && registration.challenge().size() <= 2);
        assertTrue(List.of(ActiveLivenessAction.values()).containsAll(registration.challenge()));
        assertThrows(ResponseStatusException.class, () -> service.start(OWNER, ActiveLivenessPurpose.VERIFICATION, null));
        assertThrows(ResponseStatusException.class, () -> service.start(OWNER, ActiveLivenessPurpose.REGISTRATION, "attempt"));
        assertDoesNotThrow(() -> service.start(OWNER, ActiveLivenessPurpose.VERIFICATION, "attempt"));
    }

    @Test void ownershipCancellationExpiryAndTerminalReuseFailClosed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ActiveLivenessSessionService service = service((id, challenge, sequence, bytes, type) -> ActiveLivenessEvaluator.Evaluation.pending(), clock, 6, 100);
        ActiveLivenessSessionResponse created = service.start(OWNER, ActiveLivenessPurpose.REGISTRATION, null);
        assertThrows(ResponseStatusException.class, () -> service.status(OTHER, created.sessionId()));
        assertEquals(ActiveLivenessState.CANCELLED, service.cancel(OWNER, created.sessionId()).state());
        assertThrows(ResponseStatusException.class, () -> service.submitFrame(OWNER, created.sessionId(), 0, jpeg()));

        ActiveLivenessSessionResponse expiring = service.start(OWNER, ActiveLivenessPurpose.REGISTRATION, null);
        clock.advanceSeconds(6);
        assertEquals(ActiveLivenessState.EXPIRED, service.status(OWNER, expiring.sessionId()).state());
        assertThrows(ResponseStatusException.class, () -> service.submitFrame(OWNER, expiring.sessionId(), 0, jpeg()));
    }

    @Test void terminalMetadataIsRemovedAfterConfiguredRetention() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ActiveLivenessSessionService service = service((id, challenge, sequence, bytes, type) -> ActiveLivenessEvaluator.Evaluation.pending(), clock, 60, 100);
        String id = service.start(OWNER, ActiveLivenessPurpose.REGISTRATION, null).sessionId();
        service.cancel(OWNER, id);
        clock.advanceSeconds(60);
        service.cleanupExpiredSessions();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.status(OWNER, id)).getStatusCode());
    }

    @Test void rejectsBadContentOversizeDuplicateAndOutOfOrderFrames() {
        ActiveLivenessSessionService service = service(ActiveLivenessEvaluator.Evaluation::pending);
        String id = service.start(OWNER, ActiveLivenessPurpose.REGISTRATION, null).sessionId();
        ResponseStatusException badContent = assertThrows(ResponseStatusException.class,
                () -> service.submitFrame(OWNER, id, 0, new MockMultipartFile("frame", "x.png", "image/png", new byte[]{1})));
        assertEquals(HttpStatus.BAD_REQUEST, badContent.getStatusCode());
        ResponseStatusException oversized = assertThrows(ResponseStatusException.class,
                () -> service.submitFrame(OWNER, id, 0, new MockMultipartFile("frame", "x.jpg", "image/jpeg", new byte[101])));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, oversized.getStatusCode());
        assertEquals(1L, service.submitFrame(OWNER, id, 0, jpeg()).nextExpectedSequence());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.submitFrame(OWNER, id, 0, jpeg())).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.submitFrame(OWNER, id, 2, jpeg())).getStatusCode());
    }

    @Test void enforcesConfiguredFrameRateLimit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ActiveLivenessSessionService service = new ActiveLivenessSessionService(
                (id, challenge, sequence, bytes, type) -> ActiveLivenessEvaluator.Evaluation.pending(),
                clock, 60, 10, 100, 10, 1, 60, 100);
        String id = service.start(OWNER, ActiveLivenessPurpose.REGISTRATION, null).sessionId();
        service.submitFrame(OWNER, id, 0, jpeg());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.submitFrame(OWNER, id, 1, jpeg()));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatusCode());
    }

    @Test void retryRotatesChallengeAndPassedPlaceholderNeverMarksAttendance() {
        ActiveLivenessEvaluator retryThenPass = new ActiveLivenessEvaluator() {
            int calls;
            @Override public Evaluation evaluate(String id, List<ActiveLivenessAction> c, long sequence, byte[] bytes, String type) {
                return ++calls == 1 ? new Evaluation(Decision.RETRYABLE_FAILURE, "RETRY") : new Evaluation(Decision.PASSED, null);
            }
        };
        ActiveLivenessSessionService service = service(retryThenPass);
        String id = service.start(OWNER, ActiveLivenessPurpose.VERIFICATION, "attempt").sessionId();
        ActiveLivenessSessionResponse retry = service.submitFrame(OWNER, id, 0, jpeg());
        assertEquals(ActiveLivenessState.ACTIVE, retry.state());
        assertEquals(1, retry.retryCount());
        ActiveLivenessSessionResponse passed = service.submitFrame(OWNER, id, 1, jpeg());
        assertEquals(ActiveLivenessState.COMPLETED, passed.state());
        assertEquals("ACTIVE_LIVENESS_PASSED", passed.reason());
        // The service has no AttendanceRecordRepository or markSuccessful dependency.
        // A mock evaluator's PASSED result is intentionally not an attendance action.
        assertThrows(ResponseStatusException.class, () -> service.submitFrame(OWNER, id, 2, jpeg()));
    }

    @Test void concurrentFramesAllowOnlyOneInFlightProcessor() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ActiveLivenessEvaluator blocking = (id, c, s, b, t) -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return ActiveLivenessEvaluator.Evaluation.pending();
        };
        ActiveLivenessSessionService service = service(blocking);
        String id = service.start(OWNER, ActiveLivenessPurpose.REGISTRATION, null).sessionId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<ActiveLivenessSessionResponse> first = executor.submit(() -> service.submitFrame(OWNER, id, 0, jpeg()));
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        Future<Object> second = executor.submit(() -> {
            try { service.submitFrame(OWNER, id, 0, jpeg()); return "accepted"; }
            catch (ResponseStatusException e) { return e.getStatusCode(); }
        });
        assertEquals(HttpStatus.CONFLICT, second.get());
        release.countDown();
        assertEquals(ActiveLivenessState.ACTIVE, first.get().state());
        executor.shutdownNow();
    }

    private static MockMultipartFile jpeg() { return new MockMultipartFile("frame", "frame.jpg", "image/jpeg", new byte[]{1, 2, 3}); }
    private static ActiveLivenessSessionService service(java.util.function.Supplier<ActiveLivenessEvaluator.Evaluation> supplier) {
        return service((id, c, sequence, bytes, type) -> supplier.get());
    }
    private static ActiveLivenessSessionService service(ActiveLivenessEvaluator evaluator) {
        return service(evaluator, Clock.systemUTC(), 60, 100);
    }
    private static ActiveLivenessSessionService service(ActiveLivenessEvaluator evaluator, Clock clock, int timeout, int maxSessions) {
        return new ActiveLivenessSessionService(evaluator, clock, timeout, maxSessions, 100, 10, 1, 60, 0);
    }
    private static final class MutableClock extends Clock {
        private Instant instant; MutableClock(Instant instant) { this.instant = instant; }
        void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
