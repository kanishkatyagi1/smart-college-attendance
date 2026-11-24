package com.college.attendance.service;

import com.college.attendance.dto.PendingStaffResponse;
import com.college.attendance.entity.ApprovalStatus;
import com.college.attendance.entity.Role;
import com.college.attendance.entity.Teacher;
import com.college.attendance.entity.User;
import com.college.attendance.repo.TeacherRepository;
import com.college.attendance.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class StaffApprovalService {

    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final PasswordEncoder passwordEncoder;

    public StaffApprovalService(
            UserRepository userRepository,
            TeacherRepository teacherRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.teacherRepository = teacherRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<PendingStaffResponse> getPendingTeachers() {
        return userRepository
                .findByRoleAndApprovalStatus(Role.TEACHER, ApprovalStatus.PENDING)
                .stream()
                .map(user -> {
                    Teacher teacher = teacherRepository.findByUser(user)
                            .orElse(null);

                    return new PendingStaffResponse(
                            user.getId(),
                            user.getName(),
                            user.getEmail(),
                            user.getPhone(),
                            user.getRole(),
                            teacher != null ? teacher.getEmployeeId() : null,
                            teacher != null ? teacher.getDepartment() : null,
                            user.isEmailVerified(),
                            user.getApprovalStatus()
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PendingStaffResponse> getPendingHods() {
        return userRepository
                .findByRoleAndApprovalStatus(Role.HOD, ApprovalStatus.PENDING)
                .stream()
                .map(user -> new PendingStaffResponse(
                        user.getId(),
                        user.getName(),
                        user.getEmail(),
                        user.getPhone(),
                        user.getRole(),
                        null,
                        null,
                        user.isEmailVerified(),
                        user.getApprovalStatus()
                ))
                .toList();
    }

    @Transactional
    public PendingStaffResponse approveTeacher(Long userId, String rawPassword) {
        User user = getPendingUser(userId, Role.TEACHER);

        if (teacherRepository.findByUser(user).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Teacher profile not found"
            );
        }

        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setApprovalStatus(ApprovalStatus.APPROVED);
        user.setActive(true);

        userRepository.save(user);

        return getTeacherResponse(user);
    }

    @Transactional
    public PendingStaffResponse approveHod(Long userId, String rawPassword) {
        User user = getPendingUser(userId, Role.HOD);

        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setApprovalStatus(ApprovalStatus.APPROVED);
        user.setActive(true);

        userRepository.save(user);

        return new PendingStaffResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                null,
                null,
                user.isEmailVerified(),
                user.getApprovalStatus()
        );
    }

    private User getPendingUser(Long userId, Role expectedRole) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));

        if (user.getRole() != expectedRole) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid staff role"
            );
        }

        if (!user.isEmailVerified()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Email OTP verification is required before approval"
            );
        }

        if (user.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This account is not pending approval"
            );
        }

        return user;
    }

    private PendingStaffResponse getTeacherResponse(User user) {
        Teacher teacher = teacherRepository.findByUser(user)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Teacher profile not found"
                ));

        return new PendingStaffResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                teacher.getEmployeeId(),
                teacher.getDepartment(),
                user.isEmailVerified(),
                user.getApprovalStatus()
        );
    }
}
