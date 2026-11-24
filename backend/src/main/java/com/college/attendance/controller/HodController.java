package com.college.attendance.controller;

import com.college.attendance.dto.PendingStaffResponse;
import com.college.attendance.dto.StaffApprovalRequest;
import com.college.attendance.entity.*;
import com.college.attendance.repo.*;
import com.college.attendance.service.StaffApprovalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hod")
public class HodController {

    private final UserRepository users;
    private final StudentRepository students;
    private final TeacherRepository teachers;
    private final AttendanceRecordRepository records;
    private final FeedbackComplaintRepository complaints;
    private final StaffApprovalService staffApprovalService;

    public HodController(
            UserRepository u,
            StudentRepository s,
            TeacherRepository t,
            AttendanceRecordRepository r,
            FeedbackComplaintRepository c,
            StaffApprovalService staffApprovalService) {
        this.users = u;
        this.students = s;
        this.teachers = t;
        this.records = r;
        this.complaints = c;
        this.staffApprovalService = staffApprovalService;
    }

    @GetMapping("/students")
    public List<Student> students() {
        return students.findAll();
    }

    @GetMapping("/teachers")
    public List<Teacher> teachers() {
        return teachers.findAll();
    }

    @GetMapping("/attendance")
    public List<AttendanceRecord> attendance() {
        return records.findAll();
    }

    @GetMapping("/analytics")
    public Object analytics() {
        long total = students.count();
        long present = records.count();

        return Map.of(
                "students", total,
                "attendanceRecords", present,
                "note", "For percentage analytics, aggregate PRESENT vs total scheduled sessions."
        );
    }

    @GetMapping("/complaints")
    public List<FeedbackComplaint> complaints() {
        return complaints.findAll();
    }

    @PostMapping("/user/{id}/disable")
    public Object disable(@PathVariable Long id) {
        User u = users.findById(id).orElseThrow();
        u.setActive(false);
        return users.save(u);
    }

    @GetMapping("/teachers/pending")
    public List<PendingStaffResponse> pendingTeachers() {
        return staffApprovalService.getPendingTeachers();
    }

    @PostMapping("/teachers/{userId}/approve")
    public PendingStaffResponse approveTeacher(
            @PathVariable Long userId,
            @Valid @RequestBody StaffApprovalRequest request) {
        return staffApprovalService.approveTeacher(
                userId,
                request.getPassword()
        );
    }
}
