package com.college.attendance.service;

import com.college.attendance.dto.*;
import com.college.attendance.entity.*;
import com.college.attendance.repo.UserRepository;
import com.college.attendance.repo.StudentRepository;
import com.college.attendance.repo.TeacherRepository;
import com.college.attendance.security.JwtService;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository users;
    private final StudentRepository students;
    private final TeacherRepository teachers;
    private final PasswordEncoder enc;
    private final JwtService jwt;
    private final EmailOtpService emailOtpService;

    public AuthService(
            UserRepository users,
            StudentRepository students,
            TeacherRepository teachers,
            PasswordEncoder enc,
            JwtService jwt,
            EmailOtpService emailOtpService) {
        this.users = users;
        this.students = students;
        this.teachers = teachers;
        this.enc = enc;
        this.jwt = jwt;
        this.emailOtpService = emailOtpService;
    }

    public LoginResponse login(LoginRequest r) {

        String email = normalizeEmail(r.getEmail());

        User u = users.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (!enc.matches(r.getPassword(), u.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        if (!u.isEmailVerified()) {
            throw new RuntimeException(
                    "Please verify your email using OTP before logging in");
        }

        if (u.getApprovalStatus() == ApprovalStatus.REJECTED) {
            throw new RuntimeException("Your registration has been rejected");
        }

        if (u.getApprovalStatus() == ApprovalStatus.PENDING) {
            throw new RuntimeException(
                    "Your account is awaiting approval and login credential setup");
        }

        if (!u.isActive()) {
            throw new RuntimeException("Your account is disabled");
        }

        if (r.getRole() != null && !r.getRole().isBlank()
                && !u.getRole().name().equalsIgnoreCase(r.getRole().trim())) {
            throw new RuntimeException("Role does not match account");
        }

        return new LoginResponse(
                jwt.generate(u.getId(), u.getEmail(), u.getRole().name()),
                u.getRole().name(),
                u.getId(),
                u.getName()
        );
    }

    @Transactional
    public void registerStudent(StudentRegistrationRequest r) {

        if (r.getPassword() == null
                || !r.getPassword().equals(r.getConfirmPassword())) {
            throw new RuntimeException(
                    "Password and confirm password do not match");
        }

        String email = normalizeEmail(r.getEmail());
        String rollNumber = r.getRollNumber().trim();

        if (users.findByEmail(email).isPresent()) {
            throw new RuntimeException("Email is already registered");
        }

        if (students.findByRollNumber(rollNumber).isPresent()) {
            throw new RuntimeException("Roll number is already registered");
        }

        User user = User.builder()
                .name(r.getName().trim())
                .email(email)
                .password(enc.encode(r.getPassword()))
                .phone(r.getPhone().trim())
                .role(Role.STUDENT)
                .active(false)
                .emailVerified(false)
                .approvalStatus(ApprovalStatus.PENDING)
                .build();

        User savedUser = users.save(user);

        Student student = Student.builder()
                .user(savedUser)
                .rollNumber(rollNumber)
                .course(r.getCourse().trim())
                .semester(r.getSemester())
                .section(r.getSection().trim())
                .build();

        students.save(student);

        emailOtpService.issueOtp(email);
    }

    @Transactional
    public void registerTeacher(TeacherRegistrationRequest r) {

        String email = normalizeEmail(r.getEmail());
        String employeeId = r.getEmployeeId().trim();

        if (users.findByEmail(email).isPresent()) {
            throw new RuntimeException("Email is already registered");
        }

        if (teachers.existsByEmployeeId(employeeId)) {
            throw new RuntimeException("Employee ID is already registered");
        }

        User user = User.builder()
                .name(r.getName().trim())
                .email(email)
                .password(enc.encode(generateTemporaryPassword()))
                .phone(r.getPhone().trim())
                .role(Role.TEACHER)
                .active(false)
                .emailVerified(false)
                .approvalStatus(ApprovalStatus.PENDING)
                .build();

        User savedUser = users.save(user);

        Teacher teacher = Teacher.builder()
                .user(savedUser)
                .employeeId(employeeId)
                .department(r.getDepartment().trim())
                .build();

        teachers.save(teacher);

        emailOtpService.issueOtp(email);
    }

    @Transactional
    public void registerHod(HodRegistrationRequest r) {

        String email = normalizeEmail(r.getEmail());

        if (users.findByEmail(email).isPresent()) {
            throw new RuntimeException("Email is already registered");
        }

        User user = User.builder()
                .name(r.getName().trim())
                .email(email)
                .password(enc.encode(generateTemporaryPassword()))
                .phone(r.getPhone().trim())
                .role(Role.HOD)
                .active(false)
                .emailVerified(false)
                .approvalStatus(ApprovalStatus.PENDING)
                .build();

        users.save(user);

        emailOtpService.issueOtp(email);
    }

    private String generateTemporaryPassword() {
        byte[] randomBytes = new byte[48];
        SECURE_RANDOM.nextBytes(randomBytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new RuntimeException("Email is required");
        }

        return email.trim().toLowerCase(Locale.ROOT);
    }
}
