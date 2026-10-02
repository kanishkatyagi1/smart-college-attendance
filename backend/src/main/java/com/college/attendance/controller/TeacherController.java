package com.college.attendance.controller;

import com.college.attendance.dto.SessionRequest;
import com.college.attendance.entity.Assignment;
import com.college.attendance.entity.AttendanceRecord;
import com.college.attendance.entity.AttendanceSession;
import com.college.attendance.entity.Student;
import com.college.attendance.entity.Subject;
import com.college.attendance.entity.Teacher;
import com.college.attendance.repo.AssignmentRepository;
import com.college.attendance.repo.AttendanceRecordRepository;
import com.college.attendance.repo.AttendanceSessionRepository;
import com.college.attendance.repo.StudentRepository;
import com.college.attendance.repo.SubjectRepository;
import com.college.attendance.repo.TeacherRepository;
import com.college.attendance.repo.UserRepository;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/teacher")
public class TeacherController {

    private final UserRepository users;
    private final TeacherRepository teachers;
    private final SubjectRepository subjects;
    private final AttendanceSessionRepository sessions;
    private final AttendanceRecordRepository records;
    private final StudentRepository students;
    private final AssignmentRepository assignmentRepository;

    public TeacherController(
            UserRepository users,
            TeacherRepository teachers,
            SubjectRepository subjects,
            AttendanceSessionRepository sessions,
            AttendanceRecordRepository records,
            StudentRepository students,
            AssignmentRepository assignmentRepository) {

        this.users = users;
        this.teachers = teachers;
        this.subjects = subjects;
        this.sessions = sessions;
        this.records = records;
        this.students = students;
        this.assignmentRepository = assignmentRepository;
    }

    // Get logged-in teacher
    private Teacher me() {

        String email = SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();

        return teachers.findByUser(
                users.findByEmail(email).orElseThrow(
                        () -> new RuntimeException("Teacher user not found")
                )
        ).orElseThrow(
                () -> new RuntimeException("Teacher profile not found")
        );
    }

    // =========================
    // TEACHER PROFILE
    // =========================

    @GetMapping("/profile")
    public Teacher profile() {
        return me();
    }

    // =========================
    // TEACHER SUBJECTS
    // =========================

    @GetMapping("/subjects")
    public List<Subject> subjects() {
        return subjects.findByTeacher(me());
    }

    // =========================
    // CREATE ASSIGNMENT
    // =========================

    @PostMapping("/assignment/create")
    public Assignment createAssignment(
            @RequestBody Assignment assignment) {

        Teacher teacher = me();

        // Check subject
        if (assignment.getSubject() == null ||
                assignment.getSubject().getId() == null) {

            throw new RuntimeException("Subject ID is required");
        }

        Long subjectId = assignment.getSubject().getId();

        Subject subject = subjects
                .findById(subjectId)
                .orElseThrow(
                        () -> new RuntimeException("Subject not found")
                );

        // Make sure subject belongs to logged-in teacher
        if (!subject.getTeacher().getId().equals(teacher.getId())) {

            throw new RuntimeException("Not your subject");
        }

        // Set actual database entities
        assignment.setSubject(subject);
        assignment.setTeacher(teacher);

        return assignmentRepository.save(assignment);
    }

    // =========================
    // CREATE ATTENDANCE SESSION
    // =========================

    @PostMapping("/session/create")
    public AttendanceSession create(
            @RequestBody SessionRequest r) {

        Subject sub = subjects
                .findById(r.getSubjectId())
                .orElseThrow(
                        () -> new RuntimeException("Subject not found")
                );

        Teacher teacher = me();

        // Check subject ownership
        if (!sub.getTeacher().getId().equals(teacher.getId())) {

            throw new RuntimeException("Not your subject");
        }

        LocalDateTime now = LocalDateTime.now();

        AttendanceSession session =
                AttendanceSession.builder()
                        .subject(sub)
                        .teacher(teacher)
                        .section(r.getSection())
                        .startTime(now)
                        .endTime(
                                now.plusMinutes(
                                        r.getDurationMinutes()
                                )
                        )
                        .active(true)
                        .build();

        return sessions.save(session);
    }

    // =========================
    // CLOSE ATTENDANCE SESSION
    // =========================

    @PostMapping("/session/{id}/close")
    public AttendanceSession close(
            @PathVariable Long id) {

        AttendanceSession session = sessions
                .findById(id)
                .orElseThrow(
                        () -> new RuntimeException("Session not found")
                );

        session.setActive(false);
        session.setEndTime(LocalDateTime.now());

        return sessions.save(session);
    }

    // =========================
    // TEACHER SESSION HISTORY
    // =========================

    @GetMapping("/sessions")
    public List<AttendanceSession> sessions() {

        return sessions
                .findByTeacherOrderByStartTimeDesc(me());
    }

    // =========================
    // VIEW SESSION ATTENDANCE
    // =========================

    @GetMapping("/session/{id}/attendance")
    public List<AttendanceRecord> attendance(
            @PathVariable Long id) {

        AttendanceSession session = sessions
                .findById(id)
                .orElseThrow(
                        () -> new RuntimeException("Session not found")
                );

        return records.findBySession(session);
    }

    // =========================
    // VIEW STUDENTS
    // =========================

    @GetMapping("/students")
    public List<Student> students() {
        return students.findAll();
    }
}