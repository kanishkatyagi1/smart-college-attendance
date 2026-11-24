package com.college.attendance.service;

import com.college.attendance.dto.VerificationAttemptResponse;
import com.college.attendance.entity.*;
import com.college.attendance.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AttendanceVerificationService {
    private static final Logger log = LoggerFactory.getLogger(AttendanceVerificationService.class);
    private final AttendanceSessionRepository sessions;
    private final AttendanceRecordRepository records;
    private final AttendanceVerificationAttemptRepository attempts;
    private final StudentRepository students;
    private final int expirationSeconds;

    public AttendanceVerificationService(AttendanceSessionRepository sessions,
                                         AttendanceRecordRepository records,
                                         AttendanceVerificationAttemptRepository attempts,
                                         StudentRepository students,
                                         @Value("${attendance.verification.expiration-seconds:120}") int expirationSeconds) {
        this.sessions = sessions;
        this.records = records;
        this.attempts = attempts;
        this.students = students;
        this.expirationSeconds = expirationSeconds;
    }

    @Transactional
    public VerificationAttemptResponse start(Student student, Long sessionId) {
        if (student == null || student.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Student must not be null");
        }
        // Pessimistic write lock serializes attempt creation per student across transactions
        Student lockedStudent = students.findWithLockById(student.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));

        long recentAttempts = attempts.countByStudentAndCreatedAtAfter(lockedStudent, LocalDateTime.now().minusMinutes(10));
        if (recentAttempts >= 5) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attendance verification attempts. Please wait before trying again.");
        }

        AttendanceSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendance session not found"));
        log.info("[DIAG] start: sessionId={} active={} endTime={} studentSection={} sessionSection={}",
                session.getId(), session.isActive(), session.getEndTime(),
                lockedStudent.getSection(), session.getSection());
        boolean alreadyMarked = records.existsBySessionAndStudent(session, lockedStudent);
        log.info("[DIAG] start: studentId={} alreadyMarked={} recentAttempts={}",
                lockedStudent.getId(), alreadyMarked, recentAttempts);
        validateSession(session, lockedStudent);
        rejectDuplicate(session, lockedStudent);

        LocalDateTime now = LocalDateTime.now();
        AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder()
                .id(UUID.randomUUID().toString())
                .student(lockedStudent)
                .session(session)
                .createdAt(now)
                .expiresAt(now.plusSeconds(expirationSeconds))
                .status(VerificationAttemptStatus.PENDING)
                .build();
        return VerificationAttemptResponse.from(attempts.save(attempt));
    }

    /**
     * Read-only validation used at the start of an active-liveness session.
     *
     * <p>Verifies that the attempt exists, belongs to the authenticated student,
     * and is in {@link VerificationAttemptStatus#PENDING} status. It does
     * <em>not</em> transition the attempt's status — that transition is
     * exclusively the responsibility of {@link #requirePending}, called later
     * by {@code FaceController} when the actual face photo is submitted.
     *
     * <p>Calling this during liveness-session creation prevents the attempt from
     * being consumed before the user has had a chance to pass liveness, which
     * caused HTTP 409 on the subsequent face-verification step (the attempt was
     * already {@code VERIFYING} when {@link #requirePending} was called there).
     *
     * @throws ResponseStatusException 404 if the attempt does not exist
     * @throws AccessDeniedException   if the attempt belongs to another student
     * @throws ResponseStatusException 409 if the attempt is not PENDING
     */
    @Transactional(readOnly = true)
    public void validateAttemptForLivenessStart(String attemptId, Student student) {
        // Use non-locking findById: this is a read-only pre-flight check and must not
        // acquire a PESSIMISTIC_WRITE lock (FOR UPDATE), which is incompatible with a
        // read-only transaction and causes HTTP 500 at runtime.  The status-transitioning
        // requirePending() — called later by FaceController — still uses findWithLockById.
        AttendanceVerificationAttempt attempt = attempts.findById(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Verification attempt not found"));
        if (!attempt.getStudent().getId().equals(student.getId())) {
            throw new AccessDeniedException("Verification attempt belongs to another student");
        }
        if (attempt.getStatus() != VerificationAttemptStatus.PENDING) {
            log.warn("[DIAG] validateAttemptForLivenessStart 409: attemptId.prefix={} status={} studentId={}",
                    attemptId.length() >= 8 ? attemptId.substring(0, 8) : attemptId,
                    attempt.getStatus(), student.getId());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Verification attempt has already been used");
        }
        // Session-open check: reject immediately if the teacher already closed the session,
        // so the student doesn't pass liveness only to be told the session is gone.
        validateSession(attempt.getSession(), student);
    }

    @Transactional(noRollbackFor = AttemptExpiredException.class)
    public AttendanceVerificationAttempt requirePending(String attemptId, Student student) {
        AttendanceVerificationAttempt attempt = attempts.findWithLockById(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Verification attempt not found"));
        if (!attempt.getStudent().getId().equals(student.getId())) {
            throw new AccessDeniedException("Verification attempt belongs to another student");
        }
        if (attempt.getStatus() != VerificationAttemptStatus.PENDING) {
            log.warn("[DIAG] requirePending 409 ALREADY_USED: attemptId.prefix={} status={} studentId={}",
                    attemptId.length() >= 8 ? attemptId.substring(0, 8) : attemptId,
                    attempt.getStatus(), student.getId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Verification attempt has already been used");
        }
        if (!attempt.getExpiresAt().isAfter(LocalDateTime.now())) {
            attempt.setStatus(VerificationAttemptStatus.EXPIRED);
            attempts.save(attempt);
            returnExpired();
        }
        AttendanceSession session = attempt.getSession();
        if (!session.isActive() || !session.getEndTime().isAfter(LocalDateTime.now())) {
            log.warn("[DIAG] requirePending 409 SESSION_CLOSED: attemptId.prefix={} sessionId={} studentId={}",
                    attemptId.length() >= 8 ? attemptId.substring(0, 8) : attemptId,
                    session.getId(), student.getId());
        }
        if (records.existsBySessionAndStudent(session, student)) {
            log.warn("[DIAG] requirePending 409 ALREADY_MARKED: attemptId.prefix={} sessionId={} studentId={}",
                    attemptId.length() >= 8 ? attemptId.substring(0, 8) : attemptId,
                    session.getId(), student.getId());
        }
        validateSession(attempt.getSession(), student);
        rejectDuplicate(attempt.getSession(), student);
        attempt.setStatus(VerificationAttemptStatus.VERIFYING);
        attempts.saveAndFlush(attempt);
        return attempt;
    }

    private void returnExpired() {
        // Returning from the transaction commits the EXPIRED transition before the API reports 410.
        throw new AttemptExpiredException();
    }

    @Transactional
    public void markFailed(String attemptId, Student student) {
        AttendanceVerificationAttempt attempt = attempts.findWithLockById(attemptId).orElse(null);
        if (attempt != null && attempt.getStudent().getId().equals(student.getId())
                && attempt.getStatus() == VerificationAttemptStatus.VERIFYING) {
            attempt.setStatus(VerificationAttemptStatus.FAILED);
            attempt.setCompletedAt(LocalDateTime.now());
            attempts.save(attempt);
        }
    }

    @Transactional(noRollbackFor = AttemptExpiredException.class)
    public AttendanceRecord markSuccessful(String attemptId, Student student, String method,
                                           Double latitude, Double longitude, Double accuracy, Double distance) {
        AttendanceVerificationAttempt attempt = attempts.findWithLockById(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Verification attempt not found"));
        if (!attempt.getStudent().getId().equals(student.getId())) throw new AccessDeniedException("Verification attempt belongs to another student");
        if (attempt.getStatus() != VerificationAttemptStatus.VERIFYING) throw new ResponseStatusException(HttpStatus.CONFLICT, "Verification attempt is not being verified");
        if (!attempt.getExpiresAt().isAfter(LocalDateTime.now())) {
            attempt.setStatus(VerificationAttemptStatus.EXPIRED);
            attempts.save(attempt);
            throw new AttemptExpiredException();
        }
        validateSession(attempt.getSession(), student);
        rejectDuplicate(attempt.getSession(), student);
        attempt.setStatus(VerificationAttemptStatus.SUCCEEDED);
        attempt.setCompletedAt(LocalDateTime.now());
        attempts.save(attempt);
        AttendanceRecord record = AttendanceRecord.builder().session(attempt.getSession()).student(student)
                .markedAt(LocalDateTime.now()).status("PRESENT").verificationMethod(method)
                .latitude(latitude).longitude(longitude).accuracyMeters(accuracy).distanceMeters(distance).build();
        return records.saveAndFlush(record);
    }
    
    @Transactional(noRollbackFor = AttemptExpiredException.class)
    public void resetAfterRejectedLocation(String attemptId, Student student) {
        AttendanceVerificationAttempt attempt = attempts.findWithLockById(attemptId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Verification attempt not found"));

        if (!attempt.getStudent().getId().equals(student.getId())) {
            throw new AccessDeniedException(
                    "Verification attempt belongs to another student");
        }

        if (attempt.getStatus() != VerificationAttemptStatus.VERIFYING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Verification attempt is not being verified");
        }

        if (!attempt.getExpiresAt().isAfter(LocalDateTime.now())) {
            attempt.setStatus(VerificationAttemptStatus.EXPIRED);
            attempts.save(attempt);
            throw new AttemptExpiredException();
        }

        attempt.setStatus(VerificationAttemptStatus.PENDING);
        attempts.save(attempt);
    }

    /**
     * Approach A: On infrastructure failure, consume the attempt so it cannot be reused.
     * The student must start a new attempt, naturally rate-limited to 5 attempts per 10 minutes.
     */
    @Transactional
    public void resetAfterServiceFailure(String attemptId, Student student) {
        AttendanceVerificationAttempt attempt = attempts.findWithLockById(attemptId).orElse(null);
        if (attempt != null && attempt.getStudent().getId().equals(student.getId())
                && attempt.getStatus() == VerificationAttemptStatus.VERIFYING) {
            attempt.setStatus(VerificationAttemptStatus.FAILED);
            attempt.setCompletedAt(LocalDateTime.now());
            attempts.save(attempt);
        }
    }

    private void validateSession(AttendanceSession session, Student student) {
        if (!session.isActive() || !session.getEndTime().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Attendance session is closed");
        }
        if (!session.getSection().equals(student.getSection())) {
            throw new AccessDeniedException("Student is not eligible for this attendance session");
        }
    }

    private void rejectDuplicate(AttendanceSession session, Student student) {
        if (records.existsBySessionAndStudent(session, student)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Attendance already marked");
        }
    }

    public static class AttemptExpiredException extends RuntimeException {}
}
