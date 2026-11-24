package com.college.attendance.controller;

import com.college.attendance.dto.ActiveLivenessSessionResponse;
import com.college.attendance.dto.ActiveLivenessStartRequest;
import com.college.attendance.entity.Student;
import com.college.attendance.entity.User;
import com.college.attendance.repo.StudentRepository;
import com.college.attendance.repo.UserRepository;
import com.college.attendance.service.ActiveLivenessPurpose;
import com.college.attendance.service.ActiveLivenessSessionService;
import com.college.attendance.service.AttendanceVerificationService;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Authenticated public facade for the phase-1 active-liveness protocol.
 * It deliberately contains no attendance-marking operation.
 */
@RestController
@RequestMapping("/api/face/active-liveness/sessions")
public class ActiveLivenessController {
    private final UserRepository users;
    private final StudentRepository students;
    private final AttendanceVerificationService attempts;
    private final ActiveLivenessSessionService sessions;

    public ActiveLivenessController(UserRepository users, StudentRepository students,
                                    AttendanceVerificationService attempts,
                                    ActiveLivenessSessionService sessions) {
        this.users = users;
        this.students = students;
        this.attempts = attempts;
        this.sessions = sessions;
    }

    @PostMapping
    public ActiveLivenessSessionResponse start(@RequestBody ActiveLivenessStartRequest request) {
        Student student = currentStudent();
        if (request == null || request.purpose() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "purpose is required");
        }
        ActiveLivenessSessionResponse response = sessions.start(student, request.purpose(), request.attemptId());
        if (request.purpose() == ActiveLivenessPurpose.VERIFICATION) {
            try {
                // Read-only validation: confirms the attempt exists, belongs to this student,
                // and is PENDING. Does NOT transition the attempt's status.
                // The PENDING -> VERIFYING transition happens exclusively in FaceController
                // when the student submits the final face photo after liveness succeeds.
                attempts.validateAttemptForLivenessStart(request.attemptId(), student);
            } catch (RuntimeException ex) {
                // Do not expose an unbound session if the attempt is invalid/expired/owned by another student.
                sessions.cancel(student, response.sessionId());
                throw ex;
            }
        }
        return response;
    }

    @PostMapping(value = "/{sessionId}/frames", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ActiveLivenessSessionResponse submitFrame(@PathVariable String sessionId,
                                                      @RequestParam long sequence,
                                                      @RequestPart("frame") MultipartFile frame) {
        return sessions.submitFrame(currentStudent(), sessionId, sequence, frame);
    }

    @GetMapping("/{sessionId}")
    public ActiveLivenessSessionResponse status(@PathVariable String sessionId) {
        return sessions.status(currentStudent(), sessionId);
    }

    @DeleteMapping("/{sessionId}")
    public ActiveLivenessSessionResponse cancel(@PathVariable String sessionId) {
        return sessions.cancel(currentStudent(), sessionId);
    }

    private Student currentStudent() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = users.findByEmail(email).orElseThrow(() -> new AccessDeniedException("Authenticated user not found"));
        return students.findByUser(user).orElseThrow(() -> new AccessDeniedException("Student profile not found"));
    }
}
