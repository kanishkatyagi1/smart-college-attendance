
package com.college.attendance.service;

import com.college.attendance.dto.*;
import com.college.attendance.entity.*;
import com.college.attendance.repo.*;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
public class AdminService {

    private final UserRepository users;
    private final StudentRepository students;
    private final TeacherRepository teachers;
    private final PasswordEncoder encoder;
    private final EmailOtpService emailOtpService;

    public AdminService(
            UserRepository users,
            StudentRepository students,
            TeacherRepository teachers,
            PasswordEncoder encoder,
            EmailOtpService emailOtpService) {
        this.users = users;
        this.students = students;
        this.teachers = teachers;
        this.encoder = encoder;
        this.emailOtpService = emailOtpService;
    }

    @Transactional(readOnly = true)
    public List<AdminStudentResponse> getPendingStudents() {
        return users.findByRoleAndApprovalStatus(
                        Role.STUDENT, ApprovalStatus.PENDING)
                .stream()
                .map(this::toStudentResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminStudentResponse> getAllStudents() {
        return students.findAll()
                .stream()
                .map(student -> toStudentResponse(student.getUser()))
                .toList();
    }

    private AdminStudentResponse toStudentResponse(User user) {
        Student student = students.findByUser(user)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Student profile not found"));

        return new AdminStudentResponse(
                user.getId(),
                student.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                student.getRollNumber(),
                student.getCourse(),
                student.getSemester(),
                student.getSection(),
                user.getApprovalStatus().name(),
                user.isActive()
        );
    }

    @Transactional
    public void approveStudent(Long userId) {
        User user = getUser(userId);

        if (user.getRole() != Role.STUDENT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "User is not a student");
        }

        if (user.getApprovalStatus() == ApprovalStatus.REJECTED) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Rejected registration cannot be approved");
        }

        if (!user.isEmailVerified()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Student must verify email before activation");
        }

        user.setApprovalStatus(ApprovalStatus.APPROVED);
        user.setActive(true);
        users.save(user);
    }

    @Transactional
    public void rejectStudent(Long userId) {
        User user = getUser(userId);

        if (user.getRole() != Role.STUDENT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "User is not a student");
        }

        user.setApprovalStatus(ApprovalStatus.REJECTED);
        user.setActive(false);
        users.save(user);
    }

    @Transactional
    public void createTeacher(CreateTeacherRequest request) {
        String email = normalizeEmail(request.getEmail());
        String employeeId = request.getEmployeeId().trim();

        if (users.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Email is already registered");
        }

        if (teachers.existsByEmployeeId(employeeId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Employee ID is already registered");
        }

        User user = User.builder()
                .name(request.getName().trim())
                .email(email)
                .phone(request.getPhone().trim())
                .password(encoder.encode(request.getPassword()))
                .role(Role.TEACHER)
                .active(false)
                .emailVerified(false)
                .approvalStatus(ApprovalStatus.APPROVED)
                .build();

        User savedUser = users.save(user);

        Teacher teacher = Teacher.builder()
                .user(savedUser)
                .employeeId(employeeId)
                .department(request.getDepartment().trim())
                .build();

        teachers.save(teacher);

        emailOtpService.issueOtp(email);
    }

    @Transactional
    public void createHod(CreateHodRequest request) {
        String email = normalizeEmail(request.getEmail());

        if (users.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Email is already registered");
        }

        User hod = User.builder()
                .name(request.getName().trim())
                .email(email)
                .phone(request.getPhone().trim())
                .password(encoder.encode(request.getPassword()))
                .role(Role.HOD)
                .active(false)
                .emailVerified(false)
                .approvalStatus(ApprovalStatus.APPROVED)
                .build();

        users.save(hod);

        emailOtpService.issueOtp(email);
    }

    @Transactional
    public void setAccountStatus(Long userId, boolean active) {
        User user = getUser(userId);

        if (active && (!user.isEmailVerified()
                || user.getApprovalStatus() != ApprovalStatus.APPROVED)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only email-verified approved accounts can be enabled");
        }

        user.setActive(active);
        users.save(user);
    }

    private User getUser(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User not found"));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
