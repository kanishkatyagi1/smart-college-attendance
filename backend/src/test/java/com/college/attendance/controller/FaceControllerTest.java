package com.college.attendance.controller;

import com.college.attendance.dto.FaceVerificationResult;
import com.college.attendance.entity.*;
import com.college.attendance.repo.StudentRepository;
import com.college.attendance.repo.UserRepository;
import com.college.attendance.service.AttendanceVerificationService;
import com.college.attendance.service.FaceProcessingException;
import com.college.attendance.service.FaceVerificationService;
import com.college.attendance.service.GeofenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FaceControllerTest {

    @Mock private StudentRepository students;
    @Mock private UserRepository users;
    @Mock private FaceVerificationService faceVerificationService;
    @Mock private AttendanceVerificationService verification;
    @Mock private GeofenceService geofence;

    private FaceController controller;

    private User testUser;
    private Student testStudent;
    private AttendanceSession testSession;
    private AttendanceVerificationAttempt testAttempt;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        testUser = User.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .role(Role.STUDENT)
                .active(true)
                .approvalStatus(ApprovalStatus.APPROVED)
                .build();

        testStudent = Student.builder()
                .id(10L)
                .user(testUser)
                .rollNumber("21/ICS/001")
                .build();

        testSession = AttendanceSession.builder()
                .id(100L)
                .build();

        testAttempt = AttendanceVerificationAttempt.builder()
                .id("attempt-uuid-1234")
                .student(testStudent)
                .session(testSession)
                .status(VerificationAttemptStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(2))
                .build();

        var auth = new UsernamePasswordAuthenticationToken(
                testUser.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(users.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(testUser));
        when(students.findByUser(testUser)).thenReturn(Optional.of(testStudent));

        controller = new FaceController(students, users, faceVerificationService, verification, geofence);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── 1. Successful face match marks attendance ─────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void successfulFaceMatchMarksAttendanceAndReturnsPresent() throws Exception {
        when(verification.requirePending("attempt-uuid-1234", testStudent)).thenReturn(testAttempt);
        when(geofence.check(any(), any(), any(), any()))
                .thenReturn(new GeofenceService.Result(true, "Inside zone", 10.0));

        when(faceVerificationService.verifyStudentFace(eq(testStudent), any(byte[].class)))
                .thenReturn(FaceVerificationResult.of(true, 0.88));

        AttendanceRecord record = AttendanceRecord.builder()
                .id(501L)
                .session(testSession)
                .student(testStudent)
                .status("PRESENT")
                .build();

        when(verification.markSuccessful(eq("attempt-uuid-1234"), eq(testStudent), eq("FACE"), any(), any(), any(), any()))
                .thenReturn(record);

        MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        Object response = controller.verify("attempt-uuid-1234", 28.41, 77.52, 10.0, false, image);

        assertTrue(response instanceof Map);
        Map<String, Object> map = (Map<String, Object>) response;

        assertEquals(true, map.get("verified"));
        assertEquals(true, map.get("attendanceMarked"));
        assertEquals("PRESENT", map.get("status"));
        assertEquals("FACE", map.get("verificationMethod"));
        assertEquals(501L, map.get("attendanceId"));
        assertEquals(100L, map.get("sessionId"));
        assertNotNull(map.get("similarity"));

        verify(verification).markSuccessful(eq("attempt-uuid-1234"), eq(testStudent), eq("FACE"), any(), any(), any(), any());
        verify(verification, never()).markFailed(any(), any());
    }

    // ── 2. Different face mismatch rejected & never marks attendance ───────────

    @Test
    @SuppressWarnings("unchecked")
    void differentFaceMismatchDoesNotMarkAttendance() throws Exception {
        when(verification.requirePending("attempt-uuid-1234", testStudent)).thenReturn(testAttempt);
        when(geofence.check(any(), any(), any(), any()))
                .thenReturn(new GeofenceService.Result(true, "Inside zone", 10.0));

        // Mismatch: similarity 0.35 is below threshold
        when(faceVerificationService.verifyStudentFace(eq(testStudent), any(byte[].class)))
                .thenReturn(FaceVerificationResult.of(false, 0.35));

        MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        Object response = controller.verify("attempt-uuid-1234", 28.41, 77.52, 10.0, false, image);

        assertTrue(response instanceof Map);
        Map<String, Object> map = (Map<String, Object>) response;

        assertEquals(false, map.get("verified"));
        assertEquals(false, map.get("attendanceMarked"));
        assertEquals("FACE", map.get("reason"));
        assertEquals("Face not matched", map.get("message"));

        verify(verification).markFailed("attempt-uuid-1234", testStudent);
        verify(verification, never()).markSuccessful(any(), any(), any(), any(), any(), any(), any());
    }

    // ── 3. Missing registered face returns controlled error ───────────────────

    @Test
    @SuppressWarnings("unchecked")
    void missingRegisteredFaceReturnsNotRegisteredAndDoesNotMarkAttendance() throws Exception {
        when(verification.requirePending("attempt-uuid-1234", testStudent)).thenReturn(testAttempt);
        when(geofence.check(any(), any(), any(), any()))
                .thenReturn(new GeofenceService.Result(true, "Inside zone", 10.0));

        when(faceVerificationService.verifyStudentFace(eq(testStudent), any(byte[].class)))
                .thenReturn(FaceVerificationResult.notRegistered());

        MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        Object response = controller.verify("attempt-uuid-1234", 28.41, 77.52, 10.0, false, image);

        assertTrue(response instanceof Map);
        Map<String, Object> map = (Map<String, Object>) response;

        assertEquals(false, map.get("verified"));
        assertEquals(false, map.get("attendanceMarked"));
        assertEquals("FACE", map.get("reason"));
        assertEquals("Face is not registered", map.get("message"));

        verify(verification).markFailed("attempt-uuid-1234", testStudent);
        verify(verification, never()).markSuccessful(any(), any(), any(), any(), any(), any(), any());
    }

    // ── 4. Geofence failure prevents attendance & resets attempt ──────────────

    @Test
    @SuppressWarnings("unchecked")
    void geofenceFailurePreventsAttendanceAndResetsAttempt() throws Exception {
        when(verification.requirePending("attempt-uuid-1234", testStudent)).thenReturn(testAttempt);
        when(geofence.check(any(), any(), any(), any()))
                .thenReturn(new GeofenceService.Result(false, "You are outside the ICT building", 250.0));

        MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        Object response = controller.verify("attempt-uuid-1234", 28.41, 77.52, 10.0, false, image);

        assertTrue(response instanceof Map);
        Map<String, Object> map = (Map<String, Object>) response;

        assertEquals(false, map.get("verified"));
        assertEquals(false, map.get("attendanceMarked"));
        assertEquals("LOCATION", map.get("reason"));
        assertEquals("You are outside the ICT building", map.get("message"));

        verify(verification).resetAfterRejectedLocation("attempt-uuid-1234", testStudent);
        verifyNoInteractions(faceVerificationService);
        verify(verification, never()).markSuccessful(any(), any(), any(), any(), any(), any(), any());
    }

    // ── 5. Replayed or already used attempt is rejected ───────────────────────

    @Test
    void replayedOrAlreadyUsedAttemptIsRejected() {
        when(verification.requirePending("attempt-uuid-1234", testStudent))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Verification attempt has already been used"));

        MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.verify("attempt-uuid-1234", 28.41, 77.52, 10.0, false, image));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verifyNoInteractions(faceVerificationService);
        verify(verification, never()).markSuccessful(any(), any(), any(), any(), any(), any(), any());
    }

    // ── 6. Empty image rejects with Bad Request & consumes attempt (Approach A)

    @Test
    void emptyImageThrowsBadRequestAndConsumesAttempt() {
        when(verification.requirePending("attempt-uuid-1234", testStudent)).thenReturn(testAttempt);

        MockMultipartFile emptyImage = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[0]);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.verify("attempt-uuid-1234", 28.41, 77.52, 10.0, false, emptyImage));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(verification).markFailed("attempt-uuid-1234", testStudent);
        verifyNoInteractions(faceVerificationService);
    }

    // ── 7. Flask service unavailable returns 503 & consumes attempt (Approach A)

    @Test
    void flaskServiceUnavailableReturns503AndConsumesAttempt() throws Exception {
        when(verification.requirePending("attempt-uuid-1234", testStudent)).thenReturn(testAttempt);
        when(geofence.check(any(), any(), any(), any()))
                .thenReturn(new GeofenceService.Result(true, "Inside zone", 10.0));

        when(faceVerificationService.verifyStudentFace(eq(testStudent), any(byte[].class)))
                .thenThrow(new ResourceAccessException("Flask connection refused"));

        MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.verify("attempt-uuid-1234", 28.41, 77.52, 10.0, false, image));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        verify(verification).markFailed("attempt-uuid-1234", testStudent);
        verify(verification, never()).markSuccessful(any(), any(), any(), any(), any(), any(), any());
    }

    // ── 8. Flask bad image / no face detected marks failed ────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void flaskBadImageOrNoFaceDetectedMarksFailed() throws Exception {
        when(verification.requirePending("attempt-uuid-1234", testStudent)).thenReturn(testAttempt);
        when(geofence.check(any(), any(), any(), any()))
                .thenReturn(new GeofenceService.Result(true, "Inside zone", 10.0));

        when(faceVerificationService.verifyStudentFace(eq(testStudent), any(byte[].class)))
                .thenThrow(new IllegalArgumentException("No face detected in live image"));

        MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        Object response = controller.verify("attempt-uuid-1234", 28.41, 77.52, 10.0, false, image);

        assertTrue(response instanceof Map);
        Map<String, Object> map = (Map<String, Object>) response;

        assertEquals(false, map.get("verified"));
        assertEquals(false, map.get("attendanceMarked"));
        assertEquals("FACE", map.get("reason"));
        assertEquals("Invalid image or exactly one face was not detected", map.get("message"));

        verify(verification).markFailed("attempt-uuid-1234", testStudent);
        verify(verification, never()).markSuccessful(any(), any(), any(), any(), any(), any(), any());
    }

    // ── 9. Passive anti-spoofing presentation attack rejected & marks failed ──

    @Test
    @SuppressWarnings("unchecked")
    void spoofPresentationAttackRejectedAndNeverMarksAttendance() throws Exception {
        when(verification.requirePending("attempt-uuid-1234", testStudent)).thenReturn(testAttempt);
        when(geofence.check(any(), any(), any(), any()))
                .thenReturn(new GeofenceService.Result(true, "Inside zone", 10.0));

        when(faceVerificationService.verifyStudentFace(eq(testStudent), any(byte[].class)))
                .thenReturn(FaceVerificationResult.spoofDetected(0.123, "SPOOF_DETECTED"));

        MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        Object response = controller.verify("attempt-uuid-1234", 28.41, 77.52, 10.0, false, image);

        assertTrue(response instanceof Map);
        Map<String, Object> map = (Map<String, Object>) response;

        assertEquals(false, map.get("verified"));
        assertEquals(false, map.get("attendanceMarked"));
        assertEquals("SPOOF", map.get("reason"));
        assertEquals(0.123, map.get("livenessScore"));
        assertTrue(((String) map.get("message")).contains("Presentation attack detected"));

        verify(verification).markFailed("attempt-uuid-1234", testStudent);
        verify(verification, never()).markSuccessful(eq("attempt-uuid-1234"), eq(testStudent), eq("FACE"), any(), any(), any(), any());
    }

    // ── 9b. Image-quality rejection (blur) returns QUALITY reason, not spoof ──

    @Test
    @SuppressWarnings("unchecked")
    void blurryImageQualityRejectionReturnsQualityReasonNotSpoof() throws Exception {
        when(verification.requirePending("attempt-uuid-1234", testStudent)).thenReturn(testAttempt);
        when(geofence.check(any(), any(), any(), any()))
                .thenReturn(new GeofenceService.Result(true, "Inside zone", 10.0));

        // Quality-gate rejection (blur) — NOT a presentation attack
        when(faceVerificationService.verifyStudentFace(eq(testStudent), any(byte[].class)))
                .thenReturn(FaceVerificationResult.spoofDetected(0.0, "BLURRY_IMAGE"));

        MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        Object response = controller.verify("attempt-uuid-1234", 28.41, 77.52, 10.0, false, image);

        assertTrue(response instanceof Map);
        Map<String, Object> map = (Map<String, Object>) response;

        assertEquals(false, map.get("verified"));
        assertEquals(false, map.get("attendanceMarked"));
        assertEquals("QUALITY", map.get("reason"));
        assertEquals(0.0, map.get("livenessScore"));
        // Helpful retake guidance, NOT a presentation-attack accusation
        String message = (String) map.get("message");
        assertTrue(message.toLowerCase().contains("blurry"));
        assertFalse(message.contains("Presentation attack"));

        // Attendance is still NOT marked; the attempt is still consumed (fail-closed)
        verify(verification).markFailed("attempt-uuid-1234", testStudent);
        verify(verification, never()).markSuccessful(eq("attempt-uuid-1234"), eq(testStudent), eq("FACE"), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void noFaceProcessingRejectionIsActionableAndNeverMarksAttendance() throws Exception {
        when(verification.requirePending("attempt-uuid-1234", testStudent)).thenReturn(testAttempt);
        when(geofence.check(any(), any(), any(), any()))
                .thenReturn(new GeofenceService.Result(true, "Inside zone", 10.0));
        when(faceVerificationService.verifyStudentFace(eq(testStudent), any(byte[].class)))
                .thenThrow(new FaceProcessingException("NO_FACE"));

        MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        Map<String, Object> response = (Map<String, Object>) controller.verify(
                "attempt-uuid-1234", 28.41, 77.52, 10.0, false, image);

        assertEquals(false, response.get("verified"));
        assertEquals(false, response.get("attendanceMarked"));
        assertEquals("FACE", response.get("reason"));
        assertEquals("No face detected. Center your face in the frame and retake.", response.get("message"));
        verify(verification).markFailed("attempt-uuid-1234", testStudent);
        verify(verification, never()).markSuccessful(any(), any(), any(), any(), any(), any(), any());
    }

    // ── 10. Flask 5xx server error returns 503 & consumes attempt (Approach A) ─

    @Test
    void flaskServerErrorReturns503AndConsumesAttempt() throws Exception {
        when(verification.requirePending("attempt-uuid-1234", testStudent)).thenReturn(testAttempt);
        when(geofence.check(any(), any(), any(), any()))
                .thenReturn(new GeofenceService.Result(true, "Inside zone", 10.0));

        when(faceVerificationService.verifyStudentFace(eq(testStudent), any(byte[].class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Face service crash"));

        MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.verify("attempt-uuid-1234", 28.41, 77.52, 10.0, false, image));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        verify(verification).markFailed("attempt-uuid-1234", testStudent);
        verify(verification, never()).markSuccessful(any(), any(), any(), any(), any(), any(), any());
    }

    // ── 11. Malformed face service response throws IllegalStateException -> 503 & consumes attempt ─

    @Test
    void malformedFaceServiceResponseReturns503AndConsumesAttempt() throws Exception {
        when(verification.requirePending("attempt-uuid-1234", testStudent)).thenReturn(testAttempt);
        when(geofence.check(any(), any(), any(), any()))
                .thenReturn(new GeofenceService.Result(true, "Inside zone", 10.0));

        when(faceVerificationService.verifyStudentFace(eq(testStudent), any(byte[].class)))
                .thenThrow(new IllegalStateException("Malformed face service response: is_real must be explicitly boolean"));

        MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.verify("attempt-uuid-1234", 28.41, 77.52, 10.0, false, image));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        verify(verification).markFailed("attempt-uuid-1234", testStudent);
        verify(verification, never()).markSuccessful(any(), any(), any(), any(), any(), any(), any());
    }
}
