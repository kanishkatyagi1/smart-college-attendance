package com.college.attendance.service;

import com.college.attendance.dto.VerificationAttemptResponse;
import com.college.attendance.entity.*;
import com.college.attendance.repo.*;
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
    private final AttendanceSessionRepository sessions;
    private final AttendanceRecordRepository records;
    private final AttendanceVerificationAttemptRepository attempts;
    private final int expirationSeconds;

    public AttendanceVerificationService(AttendanceSessionRepository sessions,
                                         AttendanceRecordRepository records,
                                         AttendanceVerificationAttemptRepository attempts,
                                         @Value("${attendance.verification.expiration-seconds:120}") int expirationSeconds) {
        this.sessions = sessions;
        this.records = records;
        this.attempts = attempts;
        this.expirationSeconds = expirationSeconds;
    }

    @Transactional
    public VerificationAttemptResponse start(Student student, Long sessionId) {
        AttendanceSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendance session not found"));
        validateSession(session, student);
        rejectDuplicate(session, student);
        LocalDateTime now = LocalDateTime.now();
        AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder()
                .id(UUID.randomUUID().toString()).student(student).session(session)
                .createdAt(now).expiresAt(now.plusSeconds(expirationSeconds))
                .status(VerificationAttemptStatus.PENDING).build();
        return VerificationAttemptResponse.from(attempts.save(attempt));
    }

    @Transactional(noRollbackFor = AttemptExpiredException.class)
    public AttendanceVerificationAttempt requirePending(String attemptId, Student student) {
        AttendanceVerificationAttempt attempt = attempts.findWithLockById(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Verification attempt not found"));
        if (!attempt.getStudent().getId().equals(student.getId())) {
            throw new AccessDeniedException("Verification attempt belongs to another student");
        }
        if (attempt.getStatus() != VerificationAttemptStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Verification attempt has already been used");
        }
        if (!attempt.getExpiresAt().isAfter(LocalDateTime.now())) {
            attempt.setStatus(VerificationAttemptStatus.EXPIRED);
            attempts.save(attempt);
            returnExpired();
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

    @Transactional
    public void resetAfterServiceFailure(String attemptId, Student student) {
        AttendanceVerificationAttempt attempt = attempts.findWithLockById(attemptId).orElse(null);
        if (attempt != null && attempt.getStudent().getId().equals(student.getId())
                && attempt.getStatus() == VerificationAttemptStatus.VERIFYING
                && attempt.getExpiresAt().isAfter(LocalDateTime.now())) {
            attempt.setStatus(VerificationAttemptStatus.PENDING);
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
