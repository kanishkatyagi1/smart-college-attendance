package com.college.attendance.service;

import com.college.attendance.entity.*;
import com.college.attendance.repo.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AttendanceVerificationServiceTest {
    private final AttendanceSessionRepository sessions = mock(AttendanceSessionRepository.class);
    private final AttendanceRecordRepository records = mock(AttendanceRecordRepository.class);
    private final AttendanceVerificationAttemptRepository attempts = mock(AttendanceVerificationAttemptRepository.class);
    private final StudentRepository students = mock(StudentRepository.class);
    private final AttendanceVerificationService service = new AttendanceVerificationService(sessions, records, attempts, students, 120);

    @Test void startsAttemptForEligibleStudentAndRejectsClosedOrDuplicateSessions() {
        Student student = student(1L, "CSE-A"); AttendanceSession session = session(10L, "CSE-A", true);
        when(students.findWithLockById(1L)).thenReturn(Optional.of(student));
        when(attempts.countByStudentAndCreatedAtAfter(eq(student), any())).thenReturn(0L);
        when(sessions.findById(10L)).thenReturn(Optional.of(session));
        when(records.existsBySessionAndStudent(session, student)).thenReturn(false);
        when(attempts.save(any())).thenAnswer(i -> i.getArgument(0));
        var response = service.start(student, 10L);
        assertNotNull(response.getAttemptId()); assertEquals(10L, response.getSessionId());

        session.setActive(false);
        assertThrows(ResponseStatusException.class, () -> service.start(student, 10L));
        session.setActive(true);
        when(records.existsBySessionAndStudent(session, student)).thenReturn(true);
        assertThrows(ResponseStatusException.class, () -> service.start(student, 10L));
    }

    @Test void rateLimitEnforcedWhenStudentReachesFiveAttemptsInTenMinutes() {
        Student student = student(1L, "CSE-A"); AttendanceSession session = session(10L, "CSE-A", true);
        when(students.findWithLockById(1L)).thenReturn(Optional.of(student));
        when(attempts.countByStudentAndCreatedAtAfter(eq(student), any())).thenReturn(5L);
        when(sessions.findById(10L)).thenReturn(Optional.of(session));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.start(student, 10L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Too many attendance verification attempts"));
    }

    @Test void rejectsAttemptOwnedByAnotherStudentAndExpiredAttempt() {
        Student owner = student(1L, "CSE-A"); Student other = student(2L, "CSE-A");
        AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder().id("attempt").student(owner)
                .session(session(10L, "CSE-A", true)).status(VerificationAttemptStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(1)).build();
        when(attempts.findWithLockById("attempt")).thenReturn(Optional.of(attempt));
        assertThrows(AccessDeniedException.class, () -> service.requirePending("attempt", other));

        attempt.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThrows(AttendanceVerificationService.AttemptExpiredException.class, () -> service.requirePending("attempt", owner));
        assertEquals(VerificationAttemptStatus.EXPIRED, attempt.getStatus());
    }

    @Test void successfulAttemptCreatesOneFaceAttendanceAndCannotBeReused() {
        Student student = student(1L, "CSE-A"); AttendanceSession session = session(10L, "CSE-A", true);
        AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder().id("attempt").student(student)
                .session(session).status(VerificationAttemptStatus.VERIFYING).expiresAt(LocalDateTime.now().plusMinutes(1)).build();
        when(attempts.findWithLockById("attempt")).thenReturn(Optional.of(attempt));
        when(records.existsBySessionAndStudent(session, student)).thenReturn(false);
        when(records.saveAndFlush(any())).thenAnswer(i -> { AttendanceRecord record = i.getArgument(0); record.setId(55L); return record; });
        AttendanceRecord record = service.markSuccessful("attempt", student, "FACE", 1.0, 2.0, 3.0, 4.0);
        assertEquals(55L, record.getId()); assertEquals("FACE", record.getVerificationMethod());
        assertEquals(VerificationAttemptStatus.SUCCEEDED, attempt.getStatus());
        assertThrows(ResponseStatusException.class, () -> service.markSuccessful("attempt", student, "FACE", 1.0, 2.0, 3.0, 4.0));
    }

    @Test void approachAConsumesAttemptOnServiceFailure() {
        Student student = student(1L, "CSE-A"); AttendanceSession session = session(10L, "CSE-A", true);
        AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder().id("attempt").student(student)
                .session(session).status(VerificationAttemptStatus.VERIFYING).expiresAt(LocalDateTime.now().plusMinutes(1)).build();
        when(attempts.findWithLockById("attempt")).thenReturn(Optional.of(attempt));

        service.resetAfterServiceFailure("attempt", student);

        assertEquals(VerificationAttemptStatus.FAILED, attempt.getStatus());
        assertNotNull(attempt.getCompletedAt());
        verify(attempts).save(attempt);
    }

    @Test void concurrentAttemptCreationSerializedAndEnforcesFiveAttemptLimit() throws Exception {
        Student student = student(100L, "CSE-A");
        AttendanceSession session = session(10L, "CSE-A", true);

        AttendanceSessionRepository mockSessions = mock(AttendanceSessionRepository.class);
        AttendanceRecordRepository mockRecords = mock(AttendanceRecordRepository.class);
        StudentRepository mockStudents = mock(StudentRepository.class);
        AttendanceVerificationAttemptRepository mockAttempts = mock(AttendanceVerificationAttemptRepository.class);

        when(mockSessions.findById(10L)).thenReturn(Optional.of(session));
        when(mockRecords.existsBySessionAndStudent(session, student)).thenReturn(false);

        // Simulate database pessimistic serialization lock on the student row
        ReentrantLock studentLock = new ReentrantLock();
        List<AttendanceVerificationAttempt> persistedAttempts = Collections.synchronizedList(new ArrayList<>());

        when(mockStudents.findWithLockById(100L)).thenAnswer(i -> {
            studentLock.lock();
            return Optional.of(student);
        });

        when(mockAttempts.countByStudentAndCreatedAtAfter(eq(student), any())).thenAnswer(i -> (long) persistedAttempts.size());

        when(mockAttempts.save(any(AttendanceVerificationAttempt.class))).thenAnswer(i -> {
            AttendanceVerificationAttempt a = i.getArgument(0);
            persistedAttempts.add(a);
            studentLock.unlock();
            return a;
        });

        AttendanceVerificationService concurrentService =
                new AttendanceVerificationService(mockSessions, mockRecords, mockAttempts, mockStudents, 120);

        int totalRequests = 8;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch readyLatch = new CountDownLatch(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rateLimitedCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    concurrentService.start(student, 10L);
                    successCount.incrementAndGet();
                } catch (ResponseStatusException ex) {
                    if (ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                        rateLimitedCount.incrementAndGet();
                    }
                    if (studentLock.isHeldByCurrentThread()) {
                        studentLock.unlock();
                    }
                } catch (Exception ex) {
                    if (studentLock.isHeldByCurrentThread()) {
                        studentLock.unlock();
                    }
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Release all 8 threads simultaneously
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(5, successCount.get(), "Exactly 5 concurrent requests must succeed");
        assertEquals(3, rateLimitedCount.get(), "Remaining 3 concurrent requests must receive HTTP 429");
        assertEquals(5, persistedAttempts.size(), "At most 5 attempts may be persisted for one student");
    }

    @Test void startIsAllowedAgainAfterPriorAttemptIsMarkedFailed() {
        // A student's face is rejected (spoof/quality). The backend calls
        // markFailed on the consumed attempt. start() must accept a new
        // attempt for the same session, as long as the rate limit and
        // session-open/no-duplicate constraints are still satisfied.
        Student student = student(1L, "CSE-A");
        AttendanceSession session = session(10L, "CSE-A", true);

        // First attempt was created and then failed by markFailed().
        AttendanceVerificationAttempt priorAttempt = AttendanceVerificationAttempt.builder()
                .id("old-attempt").student(student).session(session)
                .status(VerificationAttemptStatus.FAILED)
                .createdAt(LocalDateTime.now().minusSeconds(30))
                .expiresAt(LocalDateTime.now().plusMinutes(1))
                .completedAt(LocalDateTime.now().minusSeconds(5))
                .build();

        when(students.findWithLockById(1L)).thenReturn(Optional.of(student));
        // 1 prior attempt used (the failed one); still under the 5-attempt limit.
        when(attempts.countByStudentAndCreatedAtAfter(eq(student), any())).thenReturn(1L);
        when(sessions.findById(10L)).thenReturn(Optional.of(session));
        when(records.existsBySessionAndStudent(session, student)).thenReturn(false);
        when(attempts.save(any())).thenAnswer(i -> i.getArgument(0));

        var response = service.start(student, 10L);

        assertNotNull(response.getAttemptId());
        assertNotEquals("old-attempt", response.getAttemptId(),
                "Fresh attempt must have a new id, not the old consumed one");
        assertEquals(10L, response.getSessionId());
    }

    @Test void startIsBlockedWhenAttendanceAlreadyMarkedForSession() {
        // After a successful prior attempt that marked attendance, any
        // further call to start() must be rejected (duplicate guard).
        Student student = student(1L, "CSE-A");
        AttendanceSession session = session(10L, "CSE-A", true);

        when(students.findWithLockById(1L)).thenReturn(Optional.of(student));
        when(attempts.countByStudentAndCreatedAtAfter(eq(student), any())).thenReturn(1L);
        when(sessions.findById(10L)).thenReturn(Optional.of(session));
        // Attendance row already exists.
        when(records.existsBySessionAndStudent(session, student)).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.start(student, 10L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Attendance already marked"));
    }

    @Test void repeatedRetriesStayBelowRateLimitAndBlockAt5() {
        // Student gets rejected 4 times (spoof/quality) and retries each
        // time. The 5th attempt is the last one allowed; the 6th is blocked.
        Student student = student(1L, "CSE-A");
        AttendanceSession session = session(10L, "CSE-A", true);

        when(students.findWithLockById(1L)).thenReturn(Optional.of(student));
        when(sessions.findById(10L)).thenReturn(Optional.of(session));
        when(records.existsBySessionAndStudent(session, student)).thenReturn(false);
        when(attempts.save(any())).thenAnswer(i -> i.getArgument(0));

        for (long i = 0; i < 5; i++) {
            final long count = i;
            when(attempts.countByStudentAndCreatedAtAfter(eq(student), any())).thenReturn(count);
            var resp = service.start(student, 10L);
            assertNotNull(resp.getAttemptId());
        }

        // 6th call should be rate-limited.
        when(attempts.countByStudentAndCreatedAtAfter(eq(student), any())).thenReturn(5L);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.start(student, 10L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
    }

    @Test void fullRetrySequence_failedAttemptThenFreshAttemptThenSuccess() {
        // Exact runtime sequence:
        // 1. start() creates attempt-1 (PENDING)
        // 2. requirePending(attempt-1) -> VERIFYING
        // 3. markFailed(attempt-1) -> FAILED   [SPOOF or QUALITY rejection]
        // 4. start() creates attempt-2 (PENDING)  [requestFreshAttempt]
        // 5. requirePending(attempt-2) -> VERIFYING
        // 6. markSuccessful(attempt-2) -> SUCCEEDED + attendance record
        Student student = student(1L, "CSE-A");
        AttendanceSession session = session(10L, "CSE-A", true);

        // --- Step 1: create attempt-1 ---
        when(students.findWithLockById(1L)).thenReturn(Optional.of(student));
        when(attempts.countByStudentAndCreatedAtAfter(eq(student), any())).thenReturn(0L);
        when(sessions.findById(10L)).thenReturn(Optional.of(session));
        when(records.existsBySessionAndStudent(session, student)).thenReturn(false);
        when(attempts.save(any())).thenAnswer(i -> i.getArgument(0));

        var resp1 = service.start(student, 10L);
        String attemptId1 = resp1.getAttemptId();
        assertNotNull(attemptId1);

        // Build an in-memory entity for attempt-1 that the mock can return.
        AttendanceVerificationAttempt attempt1 = AttendanceVerificationAttempt.builder()
                .id(attemptId1).student(student).session(session)
                .status(VerificationAttemptStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(2)).build();
        when(attempts.findWithLockById(attemptId1)).thenReturn(Optional.of(attempt1));
        when(attempts.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        // --- Step 2: requirePending(attempt-1) -> VERIFYING ---
        AttendanceVerificationAttempt pending1 = service.requirePending(attemptId1, student);
        assertEquals(VerificationAttemptStatus.VERIFYING, pending1.getStatus());

        // --- Step 3: markFailed(attempt-1) -> FAILED ---
        service.markFailed(attemptId1, student);
        assertEquals(VerificationAttemptStatus.FAILED, attempt1.getStatus());

        // --- Step 4: start() creates attempt-2 (rate-limit now 1) ---
        when(attempts.countByStudentAndCreatedAtAfter(eq(student), any())).thenReturn(1L);
        var resp2 = service.start(student, 10L);
        String attemptId2 = resp2.getAttemptId();
        assertNotNull(attemptId2);
        assertNotEquals(attemptId1, attemptId2, "Fresh attempt must have a new id");
        assertEquals(10L, resp2.getSessionId());

        AttendanceVerificationAttempt attempt2 = AttendanceVerificationAttempt.builder()
                .id(attemptId2).student(student).session(session)
                .status(VerificationAttemptStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(2)).build();
        when(attempts.findWithLockById(attemptId2)).thenReturn(Optional.of(attempt2));

        // --- Step 5: requirePending(attempt-2) -> VERIFYING ---
        AttendanceVerificationAttempt pending2 = service.requirePending(attemptId2, student);
        assertEquals(VerificationAttemptStatus.VERIFYING, pending2.getStatus());

        // --- Step 6: markSuccessful(attempt-2) -> SUCCEEDED + attendance ---
        when(records.saveAndFlush(any())).thenAnswer(i -> {
            AttendanceRecord r = i.getArgument(0);
            r.setId(99L);
            return r;
        });
        AttendanceRecord record = service.markSuccessful(attemptId2, student, "FACE", 1.0, 2.0, 3.0, 4.0);
        assertEquals(99L, record.getId());
        assertEquals(VerificationAttemptStatus.SUCCEEDED, attempt2.getStatus());

        // attempt-1 must stay FAILED and cannot be re-opened.
        assertEquals(VerificationAttemptStatus.FAILED, attempt1.getStatus());
    }

    @Test void requirePending_throwsConflict_whenAttemptAlreadyConsumed() {
        // Covers the ALREADY_USED path: attempt is FAILED/VERIFYING/SUCCEEDED.
        Student student = student(1L, "CSE-A");
        AttendanceSession session = session(10L, "CSE-A", true);

        for (VerificationAttemptStatus nonPending : List.of(
                VerificationAttemptStatus.FAILED,
                VerificationAttemptStatus.VERIFYING,
                VerificationAttemptStatus.SUCCEEDED,
                VerificationAttemptStatus.EXPIRED)) {

            AttendanceVerificationAttempt consumed = AttendanceVerificationAttempt.builder()
                    .id("consumed").student(student).session(session)
                    .status(nonPending)
                    .expiresAt(LocalDateTime.now().plusMinutes(2)).build();
            when(attempts.findWithLockById("consumed")).thenReturn(Optional.of(consumed));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.requirePending("consumed", student));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode(),
                    "Expected 409 for status " + nonPending);
            assertTrue(ex.getReason().contains("already been used"),
                    "Expected 'already been used' in reason for status " + nonPending);
        }
    }

    @Test void requirePending_throwsConflict_whenSessionClosed() {
        Student student = student(1L, "CSE-A");
        AttendanceSession closedSession = session(10L, "CSE-A", false); // active=false

        AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder()
                .id("att").student(student).session(closedSession)
                .status(VerificationAttemptStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(2)).build();
        when(attempts.findWithLockById("att")).thenReturn(Optional.of(attempt));
        when(records.existsBySessionAndStudent(closedSession, student)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.requirePending("att", student));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("closed"));
    }

    @Test void requirePending_throwsConflict_whenAttendanceAlreadyMarked() {
        Student student = student(1L, "CSE-A");
        AttendanceSession session = session(10L, "CSE-A", true);

        AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder()
                .id("att").student(student).session(session)
                .status(VerificationAttemptStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(2)).build();
        when(attempts.findWithLockById("att")).thenReturn(Optional.of(attempt));
        // Attendance already in DB (previous successful attempt).
        when(records.existsBySessionAndStudent(session, student)).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.requirePending("att", student));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Attendance already marked"));
    }

    // -----------------------------------------------------------------------
    // validateAttemptForLivenessStart: read-only, no PESSIMISTIC_WRITE lock
    // -----------------------------------------------------------------------

    @Test void validateAttemptForLivenessStart_usesFindByIdNotFindWithLockById() {
        // validateAttemptForLivenessStart must use the non-locking findById path so
        // it is compatible with @Transactional(readOnly=true). Using findWithLockById
        // (PESSIMISTIC_WRITE / SELECT FOR UPDATE) inside a read-only transaction causes
        // an InvalidDataAccessApiUsageException at runtime (HTTP 500).
        Student student = student(1L, "CSE-A");
        AttendanceSession session = session(10L, "CSE-A", true);

        AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder()
                .id("att-1").student(student).session(session)
                .status(VerificationAttemptStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(2)).build();

        // Only findById is stubbed — findWithLockById is NOT stubbed.
        // If the implementation calls findWithLockById, the mock returns empty
        // and the method throws 404, failing the assertion below.
        when(attempts.findById("att-1")).thenReturn(Optional.of(attempt));

        // Should succeed without throwing.
        assertDoesNotThrow(() -> service.validateAttemptForLivenessStart("att-1", student));

        verify(attempts).findById("att-1");
        verify(attempts, never()).findWithLockById("att-1");
    }

    @Test void validateAttemptForLivenessStart_rejects404WhenAttemptMissing() {
        Student student = student(1L, "CSE-A");
        when(attempts.findById("missing")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.validateAttemptForLivenessStart("missing", student));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test void validateAttemptForLivenessStart_rejectsOtherStudentsAttempt() {
        Student owner = student(1L, "CSE-A");
        Student other = student(2L, "CSE-A");
        AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder()
                .id("att-2").student(owner).session(session(10L, "CSE-A", true))
                .status(VerificationAttemptStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(2)).build();
        when(attempts.findById("att-2")).thenReturn(Optional.of(attempt));

        assertThrows(AccessDeniedException.class,
                () -> service.validateAttemptForLivenessStart("att-2", other));
    }

    @Test void validateAttemptForLivenessStart_rejects409WhenNotPending() {
        Student student = student(1L, "CSE-A");
        AttendanceSession session = session(10L, "CSE-A", true);

        for (VerificationAttemptStatus nonPending : List.of(
                VerificationAttemptStatus.VERIFYING,
                VerificationAttemptStatus.FAILED,
                VerificationAttemptStatus.SUCCEEDED,
                VerificationAttemptStatus.EXPIRED)) {

            AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder()
                    .id("att-np").student(student).session(session)
                    .status(nonPending)
                    .expiresAt(LocalDateTime.now().plusMinutes(2)).build();
            when(attempts.findById("att-np")).thenReturn(Optional.of(attempt));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.validateAttemptForLivenessStart("att-np", student),
                    "Expected 409 for status " + nonPending);
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode(),
                    "Expected 409 for status " + nonPending);
        }
    }

    @Test void validateAttemptForLivenessStart_rejectsClosedSession() {
        Student student = student(1L, "CSE-A");
        AttendanceSession closed = session(10L, "CSE-A", false); // active=false
        AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder()
                .id("att-3").student(student).session(closed)
                .status(VerificationAttemptStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(2)).build();
        when(attempts.findById("att-3")).thenReturn(Optional.of(attempt));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.validateAttemptForLivenessStart("att-3", student));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("closed"));
    }

    @Test void validateAttemptForLivenessStart_requirePending_lockBehaviorUnchanged() {
        // requirePending must still use findWithLockById (PESSIMISTIC_WRITE).
        // This test confirms the lock call is present and not silently removed.
        Student student = student(1L, "CSE-A");
        AttendanceSession session = session(10L, "CSE-A", true);
        AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder()
                .id("att-lock").student(student).session(session)
                .status(VerificationAttemptStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(2)).build();

        when(attempts.findWithLockById("att-lock")).thenReturn(Optional.of(attempt));
        when(attempts.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(records.existsBySessionAndStudent(session, student)).thenReturn(false);

        service.requirePending("att-lock", student);

        verify(attempts).findWithLockById("att-lock");
        verify(attempts, never()).findById("att-lock");
    }

    private Student student(Long id, String section) { return Student.builder().id(id).section(section).build(); }
    private AttendanceSession session(Long id, String section, boolean active) { return AttendanceSession.builder().id(id).section(section).active(active).endTime(LocalDateTime.now().plusMinutes(5)).build(); }
}
