package com.college.attendance.controller;

import com.college.attendance.dto.FaceVerificationResult;
import com.college.attendance.entity.*;
import com.college.attendance.repo.*;
import com.college.attendance.service.AttendanceVerificationService;
import com.college.attendance.service.FaceVerificationService;
import com.college.attendance.service.FaceProcessingException;
import com.college.attendance.service.GeofenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/face")
public class FaceController {

    private static final Logger log = LoggerFactory.getLogger(FaceController.class);

    private final StudentRepository students;
    private final UserRepository users;
    private final FaceVerificationService faceVerificationService;
    private final AttendanceVerificationService verification;
    private final GeofenceService geofence;

    public FaceController(StudentRepository students,
                          UserRepository users,
                          FaceVerificationService faceVerificationService,
                          AttendanceVerificationService verification,
                          GeofenceService geofence) {
        this.students = students;
        this.users = users;
        this.faceVerificationService = faceVerificationService;
        this.verification = verification;
        this.geofence = geofence;
    }

    private Student currentStudent() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = users.findByEmail(email).orElseThrow(() -> new AccessDeniedException("Authenticated user not found"));
        return students.findByUser(user).orElseThrow(() -> new AccessDeniedException("Student profile not found"));
    }

    @PostMapping(value = "/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object verify(@RequestParam String attemptId,
                         @RequestParam(required = false) Double latitude,
                         @RequestParam(required = false) Double longitude,
                         @RequestParam(required = false) Double accuracy,
                         @RequestParam(required = false) Boolean mockLocation,
                         @RequestPart("image") MultipartFile image) throws IOException {

        Student student = currentStudent();
        AttendanceVerificationAttempt attempt = verification.requirePending(attemptId, student);

        if (image == null || image.isEmpty()) {
            verification.markFailed(attemptId, student);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Face image is required");
        }

        var geo = geofence.check(latitude, longitude, accuracy, mockLocation);
        if (!geo.allowed()) {
            verification.resetAfterRejectedLocation(attemptId, student);

            return Map.of(
                    "verified", false,
                    "attendanceMarked", false,
                    "reason", "LOCATION",
                    "message", geo.message()
            );
        }

        try {
            byte[] imageBytes = image.getBytes();
            FaceVerificationResult result = faceVerificationService.verifyStudentFace(student, imageBytes);

            if (!result.isFaceRegistered()) {
                log.warn("Face verification rejected: reason=FACE_NOT_REGISTERED");
                verification.markFailed(attemptId, student);
                return Map.of(
                        "verified", false,
                        "attendanceMarked", false,
                        "reason", "FACE",
                        "message", "Face is not registered"
                );
            }

            // Liveness rejection: quality failure (blur/brightness/face-size) is a
            // non-security image problem the student can fix by retaking; a spoof
            // (presentation attack) stays generic so no anti-spoof intelligence is
            // leaked to the client. Neither marks attendance; both consume the
            // attempt (fail-closed, unchanged from prior behaviour).
            if (!result.isLive()) {
                verification.markFailed(attemptId, student);
                log.warn("Face verification rejected: reason={} livenessScore={}",
                        result.getRejectionReason(), result.getLivenessScore());
                double score = Math.round(result.getLivenessScore() * 1000.0) / 1000.0;
                if (isQualityRejection(result.getRejectionReason())) {
                    return Map.of(
                            "verified", false,
                            "attendanceMarked", false,
                            "reason", "QUALITY",
                            "livenessScore", score,
                            "message", qualityMessage(result.getRejectionReason())
                    );
                }
                return Map.of(
                        "verified", false,
                        "attendanceMarked", false,
                        "reason", "SPOOF",
                        "livenessScore", score,
                        "message", "Face liveness verification failed. Presentation attack detected."
                );
            }

            if (!result.isMatched()) {
                verification.markFailed(attemptId, student);
                log.warn("Face verification rejected: reason=FACE_NOT_MATCHED similarity={}", result.getSimilarity());
                return Map.of(
                        "verified", false,
                        "attendanceMarked", false,
                        "reason", "FACE",
                        "similarity", Math.round(result.getSimilarity() * 1000.0) / 1000.0,
                        "message", "Face not matched"
                );
            }

            AttendanceRecord saved = verification.markSuccessful(
                    attempt.getId(), student, "FACE", latitude, longitude, accuracy, geo.distanceMeters());
            log.info("Face verification accepted and attendance record created: attendanceId={}", saved.getId());

            return Map.of(
                    "verified", true,
                    "attendanceMarked", true,
                    "attendanceId", saved.getId(),
                    "sessionId", attempt.getSession().getId(),
                    "status", "PRESENT",
                    "verificationMethod", "FACE",
                    "similarity", Math.round(result.getSimilarity() * 1000.0) / 1000.0
            );

        } catch (FaceProcessingException e) {
            log.warn("Face verification rejected: reason={}", e.getReason());
            verification.markFailed(attemptId, student);
            return Map.of(
                    "verified", false,
                    "attendanceMarked", false,
                    "reason", "FACE",
                    "message", faceProcessingMessage(e.getReason())
            );
        } catch (IllegalArgumentException e) {
            // Bad image or no face detected
            log.warn("Face verification rejected: reason=INVALID_IMAGE_OR_FACE_COUNT");
            verification.markFailed(attemptId, student);
            return Map.of(
                    "verified", false,
                    "attendanceMarked", false,
                    "reason", "FACE",
                    "message", "Invalid image or exactly one face was not detected"
            );
        } catch (ResourceAccessException | HttpServerErrorException e) {
            log.error("Face verification service unavailable during attempt: {}", e.getMessage());
            verification.markFailed(attemptId, student);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Face verification service is temporarily unavailable. Please start a new attempt.");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during face verification", e);
            verification.markFailed(attemptId, student);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Face verification service error. Please start a new attempt.");
        }
    }

    /** Rejection reasons that indicate an image-quality problem (not a security
     *  event). Telling the user these is safe: an attacker already knows whether
     *  their photo is blurry/dark/small, so no anti-spoof intelligence is leaked. */
    private static final Set<String> QUALITY_REASONS = Set.of(
            "BLURRY_IMAGE", "UNDEREXPOSED_IMAGE", "OVEREXPOSED_IMAGE", "FACE_TOO_SMALL");

    private static boolean isQualityRejection(String reason) {
        return reason != null && QUALITY_REASONS.contains(reason);
    }

    private static String qualityMessage(String reason) {
        if (reason == null) {
            return "Face photo quality too low. Retake with better lighting and hold steady.";
        }
        switch (reason) {
            case "BLURRY_IMAGE":
                return "Face photo is too blurry. Hold the camera steady and retake.";
            case "UNDEREXPOSED_IMAGE":
                return "Face photo is too dark. Move to better lighting and retake.";
            case "OVEREXPOSED_IMAGE":
                return "Face photo is too bright. Reduce glare and retake.";
            case "FACE_TOO_SMALL":
                return "Face is too small in the photo. Move closer and retake.";
            default:
                return "Face photo quality too low. Retake with better lighting and hold steady.";
        }
    }

    private static String faceProcessingMessage(String reason) {
        if ("NO_FACE".equals(reason)) {
            return "No face detected. Center your face in the frame and retake.";
        }
        if ("MULTIPLE_FACES".equals(reason)) {
            return "Multiple faces detected. Keep only one face in the frame and retake.";
        }
        return "Face image could not be processed. Retake with your face clearly visible.";
    }
}
