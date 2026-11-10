package com.college.attendance.controller;

import com.college.attendance.entity.AttendanceSession;
import com.college.attendance.entity.Role;
import com.college.attendance.entity.Teacher;
import com.college.attendance.entity.User;
import com.college.attendance.repo.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TeacherControllerTest {
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void rejectsClosingAnotherTeachersSession() {
        User caller = User.builder().id(1L).email("teacher1@example.test").role(Role.TEACHER).active(true).build();
        Teacher callerTeacher = Teacher.builder().id(1L).user(caller).build();
        Teacher owner = Teacher.builder().id(2L).user(User.builder().id(2L).email("teacher2@example.test").role(Role.TEACHER).build()).build();
        AttendanceSession session = AttendanceSession.builder().id(99L).teacher(owner).build();
        UserRepository users = mock(UserRepository.class); TeacherRepository teachers = mock(TeacherRepository.class);
        AttendanceSessionRepository sessions = mock(AttendanceSessionRepository.class);
        when(users.findByEmail(caller.getEmail())).thenReturn(Optional.of(caller));
        when(teachers.findByUser(caller)).thenReturn(Optional.of(callerTeacher));
        when(sessions.findById(99L)).thenReturn(Optional.of(session));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(caller.getEmail(), null));
        TeacherController controller = new TeacherController(users, teachers, mock(SubjectRepository.class), sessions,
                mock(AttendanceRecordRepository.class), mock(StudentRepository.class), mock(AssignmentRepository.class));
        assertThrows(AccessDeniedException.class, () -> controller.close(99L));
        verify(sessions, never()).save(any());
    }
}
