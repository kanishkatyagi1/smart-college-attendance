package com.college.attendance.controller;

import com.college.attendance.entity.*;
import com.college.attendance.repo.*;
import com.college.attendance.service.AttendanceVerificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/face")
public class FaceController {
    private final StudentRepository students;
    private final UserRepository users;
    private final RestTemplate rest;
    private final AttendanceVerificationService verification;
    private final com.college.attendance.service.GeofenceService geofence;

    @Value("${face.service.url}") String faceUrl;

    public FaceController(StudentRepository students, UserRepository users, RestTemplate rest,
                          AttendanceVerificationService verification,
                          com.college.attendance.service.GeofenceService geofence) {
        this.students = students; this.users = users; this.rest = rest;
        this.verification = verification; this.geofence = geofence;
    }

    private Student currentStudent() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = users.findByEmail(email).orElseThrow(() -> new AccessDeniedException("Authenticated user not found"));
        return students.findByUser(user).orElseThrow(() -> new AccessDeniedException("Student profile not found"));
    }

    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object register(@RequestPart("image") MultipartFile image) throws IOException {
        return callFaceService("/register", currentStudent(), image);
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
        var geo = geofence.check(latitude, longitude, accuracy, mockLocation);
        if (!geo.allowed()) {
            return Map.of("verified", false, "attendanceMarked", false, "reason", "LOCATION", "message", geo.message());
        }
        try {
            Object faceResponse = callFaceService("/verify", student, image);
            if (!(faceResponse instanceof Map<?, ?> result) || !Boolean.TRUE.equals(result.get("verified"))) {
                verification.markFailed(attemptId, student);
                return Map.of("verified", false, "attendanceMarked", false, "reason", "FACE", "message", "Face not matched");
            }
            AttendanceRecord saved = verification.markSuccessful(attempt.getId(), student, "FACE", latitude, longitude, accuracy, geo.distanceMeters());
            return Map.of("verified", true, "attendanceMarked", true, "attendanceId", saved.getId(),
                    "sessionId", attempt.getSession().getId(), "status", "PRESENT", "verificationMethod", "FACE");
        } catch (HttpStatusCodeException e) {
            verification.markFailed(attemptId, student);
            return Map.of("verified", false, "attendanceMarked", false, "reason", "FACE", "message", faceFailureMessage(e.getStatusCode()));
        } catch (ResourceAccessException e) {
            verification.resetAfterServiceFailure(attemptId, student);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Face verification service unavailable");
        } catch (IOException e) {
            verification.resetAfterServiceFailure(attemptId, student);
            throw e;
        }
    }

    private Object callFaceService(String path, Student student, MultipartFile image) throws IOException {
        if (image.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Face image is required");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("studentId", student.getId().toString());
        body.add("image", new ByteArrayResource(image.getBytes()) {
            @Override public String getFilename() { return image.getOriginalFilename() == null ? "face.jpg" : image.getOriginalFilename(); }
        });
        return rest.postForObject(faceUrl + path, new HttpEntity<>(body, headers()), Object.class);
    }

    private String faceFailureMessage(HttpStatusCode status) {
        if (status == HttpStatus.NOT_FOUND) return "Face is not registered";
        if (status == HttpStatus.BAD_REQUEST) return "Invalid image or exactly one face was not detected";
        return "Face verification failed";
    }

    private HttpHeaders headers() { HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.MULTIPART_FORM_DATA); return headers; }
}
