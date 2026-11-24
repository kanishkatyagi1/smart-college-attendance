package com.college.attendance.config;

import com.college.attendance.controller.DevAttendanceSessionController;
import com.college.attendance.controller.HealthController;
import com.college.attendance.entity.Role;
import com.college.attendance.entity.User;
import com.college.attendance.repo.*;
import com.college.attendance.security.JwtFilter;
import com.college.attendance.security.JwtService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DevSessionProfileSecurityTest {

    @Nested
    @WebMvcTest(controllers = {HealthController.class, DevAttendanceSessionController.class})
    @Import({SecurityConfig.class, JwtFilter.class})
    @ActiveProfiles("dev")
    static class DevProfileActiveTests {

        @Autowired MockMvc mvc;
        @MockBean JwtService jwt;
        @MockBean UserRepository users;
        @MockBean AttendanceSessionRepository sessions;
        @MockBean SubjectRepository subjects;
        @MockBean TeacherRepository teachers;

        @Test
        void permitsDevSessionEndpointsWithoutAuthenticationWhenDevProfileActive() throws Exception {
            mvc.perform(get("/api/dev/session/active"))
                    .andExpect(status().isNoContent());
        }

        @Test
        void stillRequiresStudentAuthOnStudentRoutesEvenInDevProfile() throws Exception {
            mvc.perform(get("/api/student/active-session"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void stillRequiresTeacherAuthOnTeacherRoutesEvenInDevProfile() throws Exception {
            mvc.perform(get("/api/teacher/subjects"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void stillRequiresStudentAuthOnFaceRoutesEvenInDevProfile() throws Exception {
            mvc.perform(post("/api/face/verify"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @WebMvcTest(controllers = {HealthController.class})
    @Import({SecurityConfig.class, JwtFilter.class})
    static class NonDevProfileTests {

        @Autowired MockMvc mvc;
        @MockBean JwtService jwt;
        @MockBean UserRepository users;

        @Test
        void rejectsUnauthenticatedDevSessionEndpointWhenDevProfileInactive() throws Exception {
            mvc.perform(post("/api/dev/session/create"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void returnsNotFoundForAuthenticatedDevSessionEndpointWhenDevProfileInactive() throws Exception {
            authenticate("student-token", Role.STUDENT);
            mvc.perform(post("/api/dev/session/create")
                    .header("Authorization", "Bearer student-token"))
                    .andExpect(status().isNotFound());
        }

        private void authenticate(String token, Role role) {
            User user = User.builder().id(1L).email("student@example.test")
                    .role(role).active(true).build();
            Claims claims = mock(Claims.class);
            when(claims.get("uid", Number.class)).thenReturn(user.getId());
            when(claims.getSubject()).thenReturn(user.getEmail());
            when(claims.get("role", String.class)).thenReturn(role.name());
            when(jwt.parse(token)).thenReturn(claims);
            when(users.findById(user.getId())).thenReturn(Optional.of(user));
        }
    }
}
