package com.college.attendance.controller;

import com.college.attendance.dto.StaffApprovalRequest;
import com.college.attendance.dto.PendingStaffResponse;
import com.college.attendance.service.AdminService;
import com.college.attendance.service.StaffApprovalService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final StaffApprovalService staffApprovalService;

    public AdminController(
            AdminService adminService,
            StaffApprovalService staffApprovalService) {
        this.adminService = adminService;
        this.staffApprovalService = staffApprovalService;
    }

    @GetMapping("/students/pending")
    public ResponseEntity<?> getPendingStudents() {
        return ResponseEntity.ok(adminService.getPendingStudents());
    }

    @GetMapping("/students")
    public ResponseEntity<?> getAllStudents() {
        return ResponseEntity.ok(adminService.getAllStudents());
    }

    @PostMapping("/students/{userId}/approve")
    public ResponseEntity<?> approveStudent(@PathVariable Long userId) {
        adminService.approveStudent(userId);
        return ResponseEntity.ok(
                Map.of("message", "Student approved successfully"));
    }

    @PostMapping("/students/{userId}/reject")
    public ResponseEntity<?> rejectStudent(@PathVariable Long userId) {
        adminService.rejectStudent(userId);
        return ResponseEntity.ok(
                Map.of("message", "Student registration rejected"));
    }

    @GetMapping("/teachers/pending")
    public ResponseEntity<List<PendingStaffResponse>> getPendingTeachers() {
        return ResponseEntity.ok(staffApprovalService.getPendingTeachers());
    }

    @GetMapping("/hods/pending")
    public ResponseEntity<List<PendingStaffResponse>> getPendingHods() {
        return ResponseEntity.ok(staffApprovalService.getPendingHods());
    }

    @PostMapping("/teachers/{userId}/approve")
    public ResponseEntity<?> approveTeacher(
            @PathVariable Long userId,
            @Valid @RequestBody StaffApprovalRequest request) {

        return ResponseEntity.ok(
                staffApprovalService.approveTeacher(
                        userId,
                        request.getPassword()
                ));
    }

    @PostMapping("/hods/{userId}/approve")
    public ResponseEntity<?> approveHod(
            @PathVariable Long userId,
            @Valid @RequestBody StaffApprovalRequest request) {

        return ResponseEntity.ok(
                staffApprovalService.approveHod(
                        userId,
                        request.getPassword()
                ));
    }

    @PatchMapping("/users/{userId}/status")
    public ResponseEntity<?> updateAccountStatus(
            @PathVariable Long userId,
            @RequestBody Map<String, Boolean> request) {

        Boolean active = request.get("active");

        if (active == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", "active field is required"));
        }

        adminService.setAccountStatus(userId, active);

        return ResponseEntity.ok(
                Map.of("message", active
                        ? "Account enabled successfully"
                        : "Account disabled successfully"));
    }
}
