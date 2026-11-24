package com.college.attendance.controller;

import com.college.attendance.dto.SessionRequest;
import com.college.attendance.entity.AttendanceSession;
import com.college.attendance.entity.Subject;
import com.college.attendance.entity.Teacher;
import com.college.attendance.entity.User;
import com.college.attendance.repo.AttendanceSessionRepository;
import com.college.attendance.repo.SubjectRepository;
import com.college.attendance.repo.TeacherRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DevAttendanceSessionControllerTest {

    private AttendanceSessionRepository sessions;
    private SubjectRepository subjects;
    private TeacherRepository teachers;
    private Environment env;
    private DevAttendanceSessionController controller;

    private Subject testSubject;
    private Teacher testTeacher;

    @BeforeEach
    void setUp() {
        sessions = mock(AttendanceSessionRepository.class);
        subjects = mock(SubjectRepository.class);
        teachers = mock(TeacherRepository.class);
        env = mock(Environment.class);

        when(env.acceptsProfiles(Profiles.of("dev"))).thenReturn(true);

        User teacherUser = User.builder().id(10L).name("Prof. Turing").email("turing@gbu.ac.in").build();
        testTeacher = Teacher.builder().id(1L).user(teacherUser).employeeId("EMP1").department("CSE").build();
        testSubject = Subject.builder().id(1L).code("CS801").name("Data Mining").section("CSE-A").teacher(testTeacher).build();

        controller = new DevAttendanceSessionController(sessions, subjects, teachers, env);
    }

    @Test
    void rejectsAccessWhenProfileIsNotDev() {
        when(env.acceptsProfiles(Profiles.of("dev"))).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.createSession(null, null, null, null, false));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertTrue(ex.getReason().contains("outside dev profile"));
    }

    @Test
    void createsSessionWithDefaultsWhenNoParametersProvided() {
        when(subjects.findAll()).thenReturn(List.of(testSubject));
        when(sessions.findFirstBySubjectAndSectionAndActiveTrueAndEndTimeAfter(eq(testSubject), eq("CSE-A"), any()))
                .thenReturn(Optional.empty());

        when(sessions.save(any(AttendanceSession.class))).thenAnswer(invocation -> {
            AttendanceSession s = invocation.getArgument(0);
            s.setId(100L);
            return s;
        });

        ResponseEntity<AttendanceSession> response = controller.createSession(null, null, null, null, false);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(100L, response.getBody().getId());
        assertEquals("CSE-A", response.getBody().getSection());
        assertEquals(testSubject, response.getBody().getSubject());
        assertEquals(testTeacher, response.getBody().getTeacher());
        assertTrue(response.getBody().isActive());
        assertTrue(response.getBody().getEndTime().isAfter(LocalDateTime.now()));

        verify(sessions, times(1)).save(any(AttendanceSession.class));
    }

    @Test
    void createsSessionWithSpecificSubjectAndSectionFromDto() {
        SessionRequest req = new SessionRequest();
        req.setSubjectId(1L);
        req.setSection("CSE-A");
        req.setDurationMinutes(30);

        when(subjects.findById(1L)).thenReturn(Optional.of(testSubject));
        when(sessions.findFirstBySubjectAndSectionAndActiveTrueAndEndTimeAfter(eq(testSubject), eq("CSE-A"), any()))
                .thenReturn(Optional.empty());

        when(sessions.save(any(AttendanceSession.class))).thenAnswer(invocation -> {
            AttendanceSession s = invocation.getArgument(0);
            s.setId(101L);
            return s;
        });

        ResponseEntity<AttendanceSession> response = controller.createSession(req, null, null, null, false);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(101L, response.getBody().getId());
        assertEquals("CSE-A", response.getBody().getSection());
        assertTrue(response.getBody().getEndTime().isAfter(LocalDateTime.now().plusMinutes(25)));
    }

    @Test
    void rejectsInvalidSectionForSubject() {
        SessionRequest req = new SessionRequest();
        req.setSubjectId(1L);
        req.setSection("CSE-B"); // Subject is for CSE-A

        when(subjects.findById(1L)).thenReturn(Optional.of(testSubject));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.createSession(req, null, null, null, false));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Invalid section"));
    }

    @Test
    void returnsConflictWhenActiveSessionAlreadyExists() {
        when(subjects.findAll()).thenReturn(List.of(testSubject));

        AttendanceSession existingSession = AttendanceSession.builder()
                .id(50L).subject(testSubject).teacher(testTeacher).section("CSE-A").active(true).build();

        when(sessions.findFirstBySubjectAndSectionAndActiveTrueAndEndTimeAfter(eq(testSubject), eq("CSE-A"), any()))
                .thenReturn(Optional.of(existingSession));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.createSession(null, null, null, null, false));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(sessions, never()).save(any());
    }

    @Test
    void closesExistingActiveSessionWhenCloseExistingIsTrue() {
        when(subjects.findAll()).thenReturn(List.of(testSubject));

        AttendanceSession existingSession = AttendanceSession.builder()
                .id(50L).subject(testSubject).teacher(testTeacher).section("CSE-A").active(true).build();

        when(sessions.findFirstBySubjectAndSectionAndActiveTrueAndEndTimeAfter(eq(testSubject), eq("CSE-A"), any()))
                .thenReturn(Optional.of(existingSession));

        when(sessions.save(any(AttendanceSession.class))).thenAnswer(invocation -> {
            AttendanceSession s = invocation.getArgument(0);
            if (s.getId() == null) s.setId(102L);
            return s;
        });

        ResponseEntity<AttendanceSession> response = controller.createSession(null, null, null, null, true);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertFalse(existingSession.isActive()); // Previous session was closed
        verify(sessions, times(2)).save(any(AttendanceSession.class)); // 1 for closing, 1 for new session
    }

    @Test
    void closesSessionById() {
        AttendanceSession session = AttendanceSession.builder()
                .id(55L).subject(testSubject).teacher(testTeacher).section("CSE-A").active(true).build();

        when(sessions.findById(55L)).thenReturn(Optional.of(session));
        when(sessions.save(any(AttendanceSession.class))).thenReturn(session);

        ResponseEntity<?> response = controller.closeSession(55L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(session.isActive());
        verify(sessions).save(session);
    }

    @Test
    void closesActiveSessionsBulk() {
        AttendanceSession s1 = AttendanceSession.builder().id(1L).section("CSE-A").active(true).build();
        AttendanceSession s2 = AttendanceSession.builder().id(2L).section("CSE-B").active(true).build();

        when(sessions.findAll()).thenReturn(List.of(s1, s2));

        ResponseEntity<?> response = controller.closeActive("CSE-A");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(s1.isActive());
        assertTrue(s2.isActive()); // Section B remains unaffected
        verify(sessions, times(1)).save(s1);
    }

    @Test
    void getsActiveSession() {
        LocalDateTime now = LocalDateTime.now();
        AttendanceSession active = AttendanceSession.builder()
                .id(99L).subject(testSubject).section("CSE-A").active(true)
                .startTime(now.minusMinutes(5)).endTime(now.plusMinutes(10)).build();

        when(sessions.findFirstBySectionAndActiveTrueAndStartTimeBeforeAndEndTimeAfterOrderByStartTimeDesc(
                eq("CSE-A"), any(), any()))
                .thenReturn(Optional.of(active));

        ResponseEntity<AttendanceSession> response = controller.getActiveSession("CSE-A");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(99L, response.getBody().getId());
    }
}
