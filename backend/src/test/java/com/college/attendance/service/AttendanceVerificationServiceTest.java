package com.college.attendance.service;

import com.college.attendance.entity.*;
import com.college.attendance.repo.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AttendanceVerificationServiceTest {
    private final AttendanceSessionRepository sessions = mock(AttendanceSessionRepository.class);
    private final AttendanceRecordRepository records = mock(AttendanceRecordRepository.class);
    private final AttendanceVerificationAttemptRepository attempts = mock(AttendanceVerificationAttemptRepository.class);
    private final AttendanceVerificationService service = new AttendanceVerificationService(sessions, records, attempts, 120);

    @Test void startsAttemptForEligibleStudentAndRejectsClosedOrDuplicateSessions() {
        Student student = student(1L, "CSE-A"); AttendanceSession session = session(10L, "CSE-A", true);
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

    private Student student(Long id, String section) { return Student.builder().id(id).section(section).build(); }
    private AttendanceSession session(Long id, String section, boolean active) { return AttendanceSession.builder().id(id).section(section).active(active).endTime(LocalDateTime.now().plusMinutes(5)).build(); }
}
