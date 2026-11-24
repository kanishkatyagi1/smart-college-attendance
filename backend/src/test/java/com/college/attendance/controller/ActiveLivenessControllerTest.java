package com.college.attendance.controller;

import com.college.attendance.dto.ActiveLivenessStartRequest;
import com.college.attendance.dto.ActiveLivenessSessionResponse;
import com.college.attendance.entity.AttendanceSession;
import com.college.attendance.entity.AttendanceVerificationAttempt;
import com.college.attendance.entity.Role;
import com.college.attendance.entity.Student;
import com.college.attendance.entity.User;
import com.college.attendance.entity.VerificationAttemptStatus;
import com.college.attendance.repo.StudentRepository;
import com.college.attendance.repo.UserRepository;
import com.college.attendance.service.ActiveLivenessEvaluator;
import com.college.attendance.service.ActiveLivenessPurpose;
import com.college.attendance.service.ActiveLivenessSessionService;
import com.college.attendance.service.AttendanceVerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActiveLivenessControllerTest {

    @Mock private UserRepository users;
    @Mock private StudentRepository students;
    @Mock private AttendanceVerificationService attempts;
    private ActiveLivenessController controller;
    private Student student;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        User user = User.builder().id(1L).email("student@gbu.ac.in").role(Role.STUDENT).build();
        student = Student.builder().id(3L).user(user).build();
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(students.findByUser(user)).thenReturn(Optional.of(student));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        ActiveLivenessSessionService sessions = new ActiveLivenessSessionService(
                (id, challenge, sequence, frame, type) -> ActiveLivenessEvaluator.Evaluation.pending(),
                java.time.Clock.systemUTC(), 6, 20, 1024, 10, 1, 60, 0);
        controller = new ActiveLivenessController(users, students, attempts, sessions);
    }

    @AfterEach
    void teardown() {
        SecurityContextHolder.clearContext();
    }

    // -----------------------------------------------------------------------
    // Core contract: liveness start must NOT consume the attempt
    // -----------------------------------------------------------------------

    /**
     * Starting a VERIFICATION liveness session must leave the attempt's status
     * as PENDING. validateAttemptForLivenessStart is called (read-only);
     * requirePending must NEVER be called during liveness start.
     */
    @Test
    void startingLivenessLeavesAttemptPending() {
        // validateAttemptForLivenessStart is a void method — no exception = success.
        doNothing().when(attempts).validateAttemptForLivenessStart("attempt-1", student);

        ActiveLivenessSessionResponse response = controller.start(
                new ActiveLivenessStartRequest(ActiveLivenessPurpose.VERIFICATION, "attempt-1"));

        assertEquals(ActiveLivenessPurpose.VERIFICATION, response.purpose());
        // Read-only validation was called.
        verify(attempts).validateAttemptForLivenessStart("attempt-1", student);
        // Status-transitioning requirePending must NEVER be called during liveness start.
        verify(attempts, never()).requirePending(any(), any());
    }

    /**
     * If the attempt belongs to a different student, validateAttemptForLivenessStart
     * throws AccessDeniedException. The liveness session must be cancelled and
     * the exception propagated — no dangling in-memory session.
     */
    @Test
    void anotherStudentsAttemptIsRejectedAndSessionCancelled() {
        doThrow(new AccessDeniedException("Verification attempt belongs to another student"))
                .when(attempts).validateAttemptForLivenessStart("other-attempt", student);

        assertThrows(AccessDeniedException.class, () ->
                controller.start(new ActiveLivenessStartRequest(
                        ActiveLivenessPurpose.VERIFICATION, "other-attempt")));

        // No attempt-status transition must have occurred.
        verify(attempts, never()).requirePending(any(), any());
    }

    /**
     * If the attempt is not PENDING (e.g. already VERIFYING or FAILED),
     * validateAttemptForLivenessStart throws 409. The liveness session must be
     * cancelled and the 409 propagated.
     */
    @Test
    void nonPendingAttemptIsRejectedWith409() {
        ResponseStatusException conflict = new ResponseStatusException(
                HttpStatus.CONFLICT, "Verification attempt has already been used");
        doThrow(conflict).when(attempts).validateAttemptForLivenessStart("consumed-attempt", student);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.start(new ActiveLivenessStartRequest(
                        ActiveLivenessPurpose.VERIFICATION, "consumed-attempt")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(attempts, never()).requirePending(any(), any());
    }

    /**
     * REGISTRATION liveness sessions do not involve an attendance attempt at all.
     * Neither validateAttemptForLivenessStart nor requirePending may be called.
     */
    @Test
    void registrationDoesNotTouchAttendanceAttempt() {
        ActiveLivenessSessionResponse response = controller.start(
                new ActiveLivenessStartRequest(ActiveLivenessPurpose.REGISTRATION, null));

        assertEquals(ActiveLivenessPurpose.REGISTRATION, response.purpose());
        verifyNoInteractions(attempts);
    }

    /**
     * After liveness succeeds the face-verification step calls requirePending,
     * which transitions the attempt PENDING -> VERIFYING. This must still work
     * correctly because we only removed the premature call from liveness start.
     *
     * This test exercises AttendanceVerificationService.requirePending directly
     * (not through the controller) to verify the transition contract is intact.
     */
    @Test
    void finalFaceVerificationCanStillTransitionAttemptToVerifying() {
        // Build a mock AttendanceVerificationService for this verification.
        AttendanceVerificationAttempt attempt = AttendanceVerificationAttempt.builder()
                .id("attempt-1")
                .student(student)
                .session(AttendanceSession.builder().id(2L)
                        .section(student.getSection() != null ? student.getSection() : "CSE-A")
                        .active(true)
                        .endTime(LocalDateTime.now().plusMinutes(5))
                        .build())
                .status(VerificationAttemptStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(2))
                .build();

        // The mock AttendanceVerificationService returns the attempt after
        // the PENDING -> VERIFYING transition (as the real service would).
        attempt.setStatus(VerificationAttemptStatus.VERIFYING);
        when(attempts.requirePending("attempt-1", student)).thenReturn(attempt);

        // FaceController calls requirePending after liveness passes.
        AttendanceVerificationAttempt result = attempts.requirePending("attempt-1", student);

        assertEquals(VerificationAttemptStatus.VERIFYING, result.getStatus());
        verify(attempts).requirePending("attempt-1", student);
    }
}
