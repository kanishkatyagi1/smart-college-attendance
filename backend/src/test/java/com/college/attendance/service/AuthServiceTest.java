package com.college.attendance.service;

import com.college.attendance.dto.LoginRequest;
import com.college.attendance.dto.LoginResponse;
import com.college.attendance.entity.ApprovalStatus;
import com.college.attendance.entity.Role;
import com.college.attendance.entity.User;
import com.college.attendance.repo.UserRepository;
import com.college.attendance.repo.StudentRepository;
import com.college.attendance.repo.TeacherRepository;
import com.college.attendance.security.JwtService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository users;
    @Mock StudentRepository students;
    @Mock TeacherRepository teachers;
    @Mock JwtService jwt;
    @Mock EmailOtpService emailOtpService;

    private PasswordEncoder passwords;
    private AuthService service;

    @BeforeEach
    void setUp() {
        passwords = new BCryptPasswordEncoder();
        service = new AuthService(
                users, students, teachers, passwords, jwt, emailOtpService);
    }

    @Test
    void authenticatesStudentTeacherHodAndAdminWithTheirActualRole() {
        for (Role role : Role.values()) {
            User user = user(role, true);
            when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
            when(jwt.generate(user.getId(), user.getEmail(), role.name()))
                    .thenReturn("token-" + role);

            LoginResponse response = service.login(
                    request(user.getEmail(), "password", role.name()));

            assertEquals(role.name(), response.getRole());
            assertEquals("token-" + role, response.getToken());
        }
    }

    @Test
    void rejectsWrongPasswordDisabledAccountAndRoleMismatch() {
        User active = user(Role.STUDENT, true);
        when(users.findByEmail(active.getEmail())).thenReturn(Optional.of(active));

        assertThrows(RuntimeException.class,
                () -> service.login(request(active.getEmail(), "wrong", "STUDENT")));

        assertThrows(RuntimeException.class,
                () -> service.login(request(active.getEmail(), "password", "TEACHER")));

        User disabled = user(Role.TEACHER, false);
        when(users.findByEmail(disabled.getEmail())).thenReturn(Optional.of(disabled));

        assertThrows(RuntimeException.class,
                () -> service.login(request(disabled.getEmail(), "password", "TEACHER")));

        verify(jwt, never()).generate(eq(disabled.getId()), any(), any());
    }

    @Test
    void rejectsLoginWhenEmailIsNotVerified() {
        User user = user(Role.STUDENT, true);
        user.setEmailVerified(false);

        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        assertThrows(RuntimeException.class,
                () -> service.login(
                        request(user.getEmail(), "password", "STUDENT")));

        verify(jwt, never()).generate(any(), any(), any());
    }

    @Test
    void rejectsPendingStaffLoginUntilApproval() {
        User teacher = user(Role.TEACHER, false);
        teacher.setApprovalStatus(ApprovalStatus.PENDING);

        when(users.findByEmail(teacher.getEmail()))
                .thenReturn(Optional.of(teacher));

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> service.login(
                        request(teacher.getEmail(), "password", "TEACHER")));

        assertTrue(exception.getMessage().contains("awaiting approval"));
        verify(jwt, never()).generate(any(), any(), any());
    }

    private User user(Role role, boolean active) {
        return User.builder()
                .id((long) (role.ordinal() + 1))
                .email(role.name().toLowerCase() + "@example.test")
                .password(passwords.encode("password"))
                .name(role.name())
                .role(role)
                .active(active)
                .emailVerified(true)
                .approvalStatus(ApprovalStatus.APPROVED)
                .build();
    }

    private LoginRequest request(String email, String password, String role) {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);
        request.setRole(role);
        return request;
    }
}
