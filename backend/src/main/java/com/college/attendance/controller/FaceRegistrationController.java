package com.college.attendance.controller;

import com.college.attendance.service.FaceRegistrationService;
import com.college.attendance.service.FaceProcessingException;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Locale;

@RestController
@RequestMapping("/api/face")
public class FaceRegistrationController {

    private static final Logger log = LoggerFactory.getLogger(FaceRegistrationController.class);
    private final FaceRegistrationService faceService;

    public FaceRegistrationController(FaceRegistrationService faceService) {
        this.faceService = faceService;
    }

    /**
     * POST /api/face/register  multipart/form-data
     *
     * <p>Accepts a face image uploaded as the {@code image} part, delegates
     * extraction + encrypted persistence to {@link FaceRegistrationService},
     * and returns a {@link FaceRegistrationResponse} that Android can
     * deserialise — it checks {@code result.registered}.
     *
     * <p>All response bodies are serialised by Jackson; no manual JSON
     * string concatenation is used (S-5).
     */
    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<FaceRegistrationResponse> register(
            @AuthenticationPrincipal String email,
            @RequestParam("image") MultipartFile image) {

        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(FaceRegistrationResponse.failure("No image supplied"));
        }

        try {
            byte[] bytes = image.getBytes();
            faceService.registerFace(email, bytes);
            // Android FaceCaptureActivity checks result.registered == true
            return ResponseEntity.ok(
                    FaceRegistrationResponse.success("Face registered successfully"));

        } catch (DataIntegrityViolationException e) {
            // S-3: Concurrent duplicate registration — the DB UNIQUE constraint on
            // student_id fired.  The @Transactional rollback has already completed
            // before this point, so there is no inconsistent state.
            if (isDuplicateStudentConstraint(e)) {
                log.warn("Concurrent duplicate face registration attempt detected");
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(FaceRegistrationResponse.failure("Face already registered"));
            }
            log.error("Database integrity violation during face registration", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(FaceRegistrationResponse.failure("Internal server error"));

        } catch (FaceProcessingException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(FaceRegistrationResponse.failure(faceProcessingMessage(e.getReason())));

        } catch (IllegalArgumentException e) {
            // Validation errors from Flask (bad image / no face / multiple faces).
            // H-1: Use a fixed generic message — never pass e.getMessage() to the client,
            // which would create a passthrough risk if any future code throws
            // IllegalArgumentException with sensitive content (S-4 hardening).
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(FaceRegistrationResponse.failure("Face image could not be processed"));

        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("already registered")) {
                // Sequential duplicate detected before Flask call
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(FaceRegistrationResponse.failure("Face already registered"));
            }
            if (msg != null && (msg.contains("not active") || msg.contains("not approved"))) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(FaceRegistrationResponse.failure(
                                "Student account is not active or not approved"));
            }
            if (msg != null && msg.contains("unavailable")) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(FaceRegistrationResponse.failure("Face service is currently unavailable"));
            }
            // All other IllegalStateException (extraction failure, bad embedding, etc.)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(FaceRegistrationResponse.failure("Face image could not be processed"));

        } catch (IOException e) {
            log.error("Failed to read image bytes", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(FaceRegistrationResponse.failure("Unable to read the uploaded image"));

        } catch (Exception e) {
            log.error("Unexpected error during face registration", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(FaceRegistrationResponse.failure("Internal server error"));
        }
    }

    /**
     * Determines whether a DataIntegrityViolationException represents a genuine
     * duplicate entry on the student_id unique constraint.
     *
     * <p>Non-duplicate integrity violations (e.g., nullability, foreign key, data truncation)
     * are disqualified so they return HTTP 500 rather than masking as duplicate registrations.
     */
    private boolean isDuplicateStudentConstraint(DataIntegrityViolationException e) {
        StringBuilder sb = new StringBuilder();
        int errorCode = 0;
        String constraintName = null;

        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                sb.append(" ").append(msg);
            }
            if (current instanceof ConstraintViolationException cve) {
                if (cve.getConstraintName() != null) {
                    constraintName = cve.getConstraintName();
                }
                errorCode = cve.getErrorCode();
            } else if (current instanceof SQLException sqlEx) {
                if (errorCode == 0) {
                    errorCode = sqlEx.getErrorCode();
                }
            }
            current = current.getCause();
        }

        String combined = sb.toString().toLowerCase(Locale.ROOT);

        // Disqualify known non-duplicate integrity violations
        if (combined.contains("cannot be null")
                || combined.contains("null or transient value")
                || combined.contains("foreign key")
                || combined.contains("data truncation")
                || combined.contains("data too long")
                || combined.contains("check constraint")) {
            return false;
        }

        // Check constraint name if populated by Hibernate
        if (constraintName != null) {
            String lower = constraintName.toLowerCase(Locale.ROOT);
            if (lower.contains("student")) {
                return true;
            }
        }

        // MySQL error 1062 = ER_DUP_ENTRY
        if (errorCode == 1062) {
            if (combined.contains("student") || combined.contains("face_embeddings") || combined.contains("duplicate entry")) {
                return true;
            }
        }

        // Check textual indicators for duplicate on student
        boolean isDuplicate = combined.contains("duplicate")
                || combined.contains("unique")
                || combined.contains("uk_");
        boolean isStudent = combined.contains("student");

        return isDuplicate && isStudent;
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
