package com.college.attendance.config;

import com.college.attendance.controller.HealthController;
import com.college.attendance.entity.Role;
import com.college.attendance.entity.User;
import com.college.attendance.repo.UserRepository;
import com.college.attendance.security.JwtFilter;
import com.college.attendance.security.JwtService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, JwtFilter.class})
class SecurityAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockBean JwtService jwt;
    @MockBean UserRepository users;

    @Test void rejectsMissingAndMalformedJwtOnProtectedRoutes() throws Exception {
        mvc.perform(get("/api/student/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/profile").header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized());
    }

    @Test void rejectsStudentAccessToTeacherAndHodRoutes() throws Exception {
        authenticate("student-token", Role.STUDENT);
        mvc.perform(get("/api/teacher/subjects").header("Authorization", "Bearer student-token"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/hod/students").header("Authorization", "Bearer student-token"))
                .andExpect(status().isForbidden());
    }

    @Test void acceptsMatchingRolesAtTheirRouteBoundary() throws Exception {
        authenticate("teacher-token", Role.TEACHER);
        mvc.perform(get("/api/teacher/subjects").header("Authorization", "Bearer teacher-token"))
                .andExpect(status().isNotFound());
        authenticate("hod-token", Role.HOD);
        mvc.perform(get("/api/hod/students").header("Authorization", "Bearer hod-token"))
                .andExpect(status().isNotFound());
    }

    private void authenticate(String token, Role role) {
        User user = User.builder().id((long) (role.ordinal() + 1)).email(role.name().toLowerCase() + "@example.test")
                .role(role).active(true).build();
        Claims claims = mock(Claims.class);
        when(claims.get("uid", Number.class)).thenReturn(user.getId());
        when(claims.getSubject()).thenReturn(user.getEmail());
        when(claims.get("role", String.class)).thenReturn(role.name());
        when(jwt.parse(token)).thenReturn(claims);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
    }
}
