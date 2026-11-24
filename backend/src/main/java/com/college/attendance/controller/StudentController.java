
package com.college.attendance.controller;

import com.college.attendance.dto.MarkAttendanceRequest;
import com.college.attendance.dto.SubjectAttendanceSummary;
import com.college.attendance.dto.VerificationAttemptRequest;
import com.college.attendance.dto.VerificationAttemptResponse;
import com.college.attendance.entity.*;
import com.college.attendance.repo.*;
import com.college.attendance.service.AttendanceVerificationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/student")
public class StudentController {

    private static final Logger log = LoggerFactory.getLogger(StudentController.class);

    private final UserRepository users;
    private final StudentRepository students;
    private final AttendanceSessionRepository sessions;
    private final AttendanceRecordRepository records;
    private final AssignmentRepository assignments;
    private final SubjectRepository subjects;
    private final AttendanceVerificationService verification;

    public StudentController(
            UserRepository u,
            StudentRepository s,
            AttendanceSessionRepository as,
            AttendanceRecordRepository ar,
            AssignmentRepository a,
            SubjectRepository sub,
            AttendanceVerificationService verification) {

        users = u;
        students = s;
        sessions = as;
        records = ar;
        assignments = a;
        subjects = sub;
        this.verification = verification;
    }

    private Student me() {
        String e = SecurityContextHolder.getContext()
                .getAuthentication().getName();

        return students.findByUser(
                users.findByEmail(e).orElseThrow()
        ).orElseThrow();
    }

    @GetMapping("/profile")
    public Student profile() {
        return me();
    }

    @GetMapping("/attendance")
    public List<AttendanceRecord> attendance() {
        return records.findByStudentOrderByMarkedAtDesc(me());
    }

    @GetMapping("/attendance/summary")
    public List<SubjectAttendanceSummary> attendanceSummary() {
        Student student = me();

        List<Subject> studentSubjects = subjects.findBySectionAndSemester(
            student.getSection(), student.getSemester()
        );

        if (studentSubjects.isEmpty()) {
            return Collections.emptyList();
        }

        List<AttendanceSession> completedSessions =
            sessions.findBySectionAndSubjectInAndEndTimeBefore(
                student.getSection(), studentSubjects, LocalDateTime.now()
            );

        Map<Long, Integer> totalBySubject = new HashMap<>();
        Map<Long, Integer> attendedBySubject = new HashMap<>();

        for (AttendanceSession session : completedSessions) {
            Long subjectId = session.getSubject().getId();
            totalBySubject.merge(subjectId, 1, Integer::sum);
        }

        if (!completedSessions.isEmpty()) {
            List<AttendanceRecord> studentRecords =
                records.findByStudentAndSessionIn(student, completedSessions);

            for (AttendanceRecord record : studentRecords) {
                if ("PRESENT".equalsIgnoreCase(record.getStatus())) {
                    Long subjectId = record.getSession().getSubject().getId();
                    attendedBySubject.merge(subjectId, 1, Integer::sum);
                }
            }
        }

        List<SubjectAttendanceSummary> result = new ArrayList<>();

        for (Subject subject : studentSubjects) {
            Long id = subject.getId();
            int total = totalBySubject.getOrDefault(id, 0);
            int attended = attendedBySubject.getOrDefault(id, 0);
            double percentage = total == 0 ? 0.0
                : Math.round(attended * 1000.0 / total) / 10.0;

            result.add(new SubjectAttendanceSummary(
                id, subject.getCode(), subject.getName(),
                total, attended, percentage
            ));
        }

        return result;
    }
    @GetMapping("/active-session")
    public ResponseEntity<AttendanceSession> active() {

        LocalDateTime now = LocalDateTime.now();

        return sessions
                .findFirstBySectionAndActiveTrueAndStartTimeBeforeAndEndTimeAfterOrderByStartTimeDesc(
                        me().getSection(), now, now
                )
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/attendance/verification-attempt")
    public VerificationAttemptResponse startVerification(
            @RequestBody VerificationAttemptRequest request) {

        if (request.getSessionId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "sessionId is required"
            );
        }

        Student student = me();
        // DIAG: log attempt-creation context (no PII beyond student id).
        log.info("[DIAG] startVerification: studentId={} sessionId={}",
                student.getId(), request.getSessionId());

        VerificationAttemptResponse resp = verification.start(student, request.getSessionId());

        log.info("[DIAG] startVerification: created attemptId.prefix={} sessionId={} status={}",
                resp.getAttemptId() != null && resp.getAttemptId().length() >= 8
                        ? resp.getAttemptId().substring(0, 8) : resp.getAttemptId(),
                resp.getSessionId(),
                resp.getStatus());

        return resp;
    }

    @PostMapping("/attendance/mark")
    public Object mark(@RequestBody MarkAttendanceRequest ignored) {
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Attendance is recorded only after server-side face verification"
        );
    }

    @GetMapping("/assignments")
    public List<Assignment> assignments() {
        return assignments.findBySubjectIn(
                subjects.findBySection(me().getSection())
        );
    }
}
