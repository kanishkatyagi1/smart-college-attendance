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

    @Value("${face.service.url}")
    String faceUrl;

    public FaceController(
            StudentRepository students,
            UserRepository users,
            RestTemplate rest,
            AttendanceSessionRepository sessions,
            AttendanceRecordRepository records) {

        this.students = students;
        this.users = users;
        this.rest = rest;
        this.sessions = sessions;
        this.records = records;
    }

    // =========================
    // FACE REGISTRATION
    // =========================
    @PostMapping(
            value = "/register/{studentId}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Object register(
            @PathVariable Long studentId,
            @RequestPart("image") MultipartFile image) throws IOException {

        // Check student exists
        students.findById(studentId).orElseThrow();

        MultiValueMap<String, Object> body =
                new LinkedMultiValueMap<>();

        body.add("studentId", studentId.toString());

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
            @RequestParam Long studentId,
            @RequestParam Long sessionId,
            @RequestPart("image") MultipartFile image) throws IOException {

        // 1. Find student
        Student student =
                students.findById(studentId).orElseThrow();

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

        body.add("studentId", studentId.toString());

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
                            .build();

            AttendanceRecord saved =
                    records.save(attendance);

            // 12. Successful response
            return Map.of(
                    "verified", true,
                    "attendanceMarked", true,
                    "attendanceId", saved.getId(),
                    "studentId", studentId,
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