package com.college.attendance.controller;

import com.college.attendance.entity.*;
import com.college.attendance.repo.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/face")
public class FaceController {

    private final StudentRepository students;
    private final UserRepository users;
    private final RestTemplate rest;
    private final AttendanceSessionRepository sessions;
    private final AttendanceRecordRepository records;
    private final com.college.attendance.service.GeofenceService geofence;

    @Value("${face.service.url}")
    String faceUrl;

    public FaceController(
            StudentRepository students,
            UserRepository users,
            RestTemplate rest,
            AttendanceSessionRepository sessions,
            AttendanceRecordRepository records,
            com.college.attendance.service.GeofenceService geofence) {

        this.students = students;
        this.users = users;
        this.rest = rest;
        this.sessions = sessions;
        this.records = records;
        this.geofence = geofence;
    }

    private Student currentStudent() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = users.findByEmail(email).orElseThrow(() -> new AccessDeniedException("Authenticated user not found"));
        return students.findByUser(user).orElseThrow(() -> new AccessDeniedException("Student profile not found"));
    }

    @PostMapping(
            value = "/register",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Object register(
            @RequestPart("image") MultipartFile image) throws IOException {
        Student student = currentStudent();

        MultiValueMap<String, Object> body =
                new LinkedMultiValueMap<>();

        body.add("studentId", student.getId().toString());

        body.add("image", new ByteArrayResource(image.getBytes()) {
            @Override
            public String getFilename() {
                return image.getOriginalFilename();
            }
        });

        try {

            return rest.postForObject(
                    faceUrl + "/register",
                    new HttpEntity<>(body, headers()),
                    Object.class
            );

        } catch (Exception e) {

            return Map.of(
                    "registered", false,
                    "message",
                    "Face service unavailable: " + e.getMessage()
            );
        }
    }

    // =========================
    // FACE VERIFICATION + ATTENDANCE
    // =========================
    @PostMapping(
            value = "/verify",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Object verify(
            @RequestParam Long sessionId,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false) Double accuracy,
            @RequestParam(required = false) Boolean mockLocation,
            @RequestPart("image") MultipartFile image) throws IOException {

        // Identity is always taken from the authenticated JWT principal.
        Student student = currentStudent();

        // 2. Find attendance session
        AttendanceSession session =
                sessions.findById(sessionId).orElseThrow();

        // 3. Check session active
        if (!session.isActive()) {

            return Map.of(
                    "verified", false,
                    "attendanceMarked", false,
                    "message", "Attendance session is closed"
            );
        }

        // 4. Check session expiry
        if (session.getEndTime().isBefore(LocalDateTime.now())) {

            return Map.of(
                    "verified", false,
                    "attendanceMarked", false,
                    "message", "Attendance session has expired"
            );
        }

        // 5. Check section
        if (!session.getSection().equals(student.getSection())) {

            return Map.of(
                    "verified", false,
                    "attendanceMarked", false,
                    "message", "Student section does not match session"
            );
        }

        // 5b. Geofence: student must be physically inside the ICT zone
        var geo = geofence.check(latitude, longitude, accuracy, mockLocation);

        if (!geo.allowed()) {

            return Map.of(
                    "verified", false,
                    "attendanceMarked", false,
                    "reason", "LOCATION",
                    "message", geo.message()
            );
        }

        // 6. Check duplicate attendance
        if (records.existsBySessionAndStudent(session, student)) {

            return Map.of(
                    "verified", false,
                    "attendanceMarked", false,
                    "message", "Attendance already marked"
            );
        }

        // 7. Prepare image for Python face service
        MultiValueMap<String, Object> body =
                new LinkedMultiValueMap<>();

        body.add("studentId", student.getId().toString());

        body.add("image", new ByteArrayResource(image.getBytes()) {
            @Override
            public String getFilename() {
                return image.getOriginalFilename();
            }
        });

        try {

            // 8. Send image to Python/OpenCV service
            Object faceResponse = rest.postForObject(
                    faceUrl + "/verify",
                    new HttpEntity<>(body, headers()),
                    Object.class
            );

            // 9. Read Python response
            if (!(faceResponse instanceof Map<?, ?>)) {

                return Map.of(
                        "verified", false,
                        "attendanceMarked", false,
                        "message", "Invalid response from face service"
                );
            }

            Map<?, ?> result = (Map<?, ?>) faceResponse;

            // 10. IMPORTANT:
            // Check actual face verification result
            if (!Boolean.TRUE.equals(result.get("verified"))) {

                Object message = result.get("message");

                return Map.of(
                        "verified", false,
                        "attendanceMarked", false,
                        "message",
                        message != null
                                ? message.toString()
                                : "Face not matched"
                );
            }

            // 11. Face verified -> Save attendance
            AttendanceRecord attendance =
                    AttendanceRecord.builder()
                            .session(session)
                            .student(student)
                            .markedAt(LocalDateTime.now())
                            .status("PRESENT")
                            .verificationMethod("FACE")
                            .latitude(latitude)
                            .longitude(longitude)
                            .accuracyMeters(accuracy)
                            .distanceMeters(geo.distanceMeters())
                            .build();

            AttendanceRecord saved =
                    records.save(attendance);

            // 12. Successful response
            return Map.of(
                    "verified", true,
                    "attendanceMarked", true,
                    "attendanceId", saved.getId(),
                    "studentId", student.getId(),
                    "sessionId", sessionId,
                    "status", "PRESENT",
                    "verificationMethod", "FACE"
            );

        } catch (Exception e) {

            return Map.of(
                    "verified", false,
                    "attendanceMarked", false,
                    "message",
                    "Face service unavailable: " + e.getMessage()
            );
        }
    }

    // =========================
    // HTTP HEADERS
    // =========================
    private HttpHeaders headers() {

        HttpHeaders h = new HttpHeaders();

        h.setContentType(
                MediaType.MULTIPART_FORM_DATA
        );

        return h;
    }
}
