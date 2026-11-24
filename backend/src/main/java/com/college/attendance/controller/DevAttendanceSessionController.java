package com.college.attendance.controller;

import com.college.attendance.dto.SessionRequest;
import com.college.attendance.entity.AttendanceSession;
import com.college.attendance.entity.Subject;
import com.college.attendance.entity.Teacher;
import com.college.attendance.repo.AttendanceSessionRepository;
import com.college.attendance.repo.SubjectRepository;
import com.college.attendance.repo.TeacherRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Development-only controller for creating and managing attendance sessions
 * without requiring teacher credentials.
 *
 * <p>Strictly active only when the Spring profile {@code dev} is active.
 * Throws 404 if accessed outside the {@code dev} profile.</p>
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev/session")
public class DevAttendanceSessionController {

    private static final Logger log = LoggerFactory.getLogger(DevAttendanceSessionController.class);

    private final AttendanceSessionRepository sessions;
    private final SubjectRepository subjects;
    private final TeacherRepository teachers;
    private final Environment env;

    public DevAttendanceSessionController(
            AttendanceSessionRepository sessions,
            SubjectRepository subjects,
            TeacherRepository teachers,
            Environment env) {
        this.sessions = sessions;
        this.subjects = subjects;
        this.teachers = teachers;
        this.env = env;
    }

    private void ensureDevProfile() {
        if (env == null || !env.acceptsProfiles(Profiles.of("dev"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint not available outside dev profile");
        }
    }

    /**
     * Creates an active attendance session for development/testing.
     *
     * <p>Uses existing Subject, Teacher, Section, and timing rules.
     * Can accept parameters via body {@link SessionRequest} or query parameters.
     * If no parameters are provided, automatically defaults to the first available subject
     * and its associated section and teacher.</p>
     */
    @PostMapping("/create")
    public ResponseEntity<AttendanceSession> createSession(
            @RequestBody(required = false) SessionRequest request,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String section,
            @RequestParam(required = false) Integer durationMinutes,
            @RequestParam(defaultValue = "false") boolean closeExisting) {

        ensureDevProfile();

        Long targetSubjectId = request != null && request.getSubjectId() != null
                ? request.getSubjectId()
                : subjectId;

        String targetSection = request != null && request.getSection() != null && !request.getSection().isBlank()
                ? request.getSection().trim()
                : (section != null && !section.isBlank() ? section.trim() : null);

        int targetDuration = request != null && request.getDurationMinutes() > 0
                ? request.getDurationMinutes()
                : (durationMinutes != null && durationMinutes > 0 ? durationMinutes : 15);

        Subject sub;
        if (targetSubjectId != null) {
            sub = subjects.findById(targetSubjectId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Subject not found with id " + targetSubjectId));
        } else if (targetSection != null) {
            List<Subject> bySec = subjects.findBySection(targetSection);
            if (!bySec.isEmpty()) {
                sub = bySec.get(0);
            } else {
                List<Subject> all = subjects.findAll();
                if (all.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No subjects found in database");
                }
                sub = all.get(0);
            }
        } else {
            List<Subject> all = subjects.findAll();
            if (all.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No subjects found in database");
            }
            sub = all.get(0);
        }

        if (targetSection == null) {
            targetSection = sub.getSection();
        } else if (sub.getSection() != null && !sub.getSection().equals(targetSection)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid section for this subject");
        }

        Teacher teacher = sub.getTeacher();
        if (teacher == null) {
            teacher = teachers.findAll().stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "No teachers found in database"));
        }

        LocalDateTime now = LocalDateTime.now();

        Optional<AttendanceSession> active = sessions
                .findFirstBySubjectAndSectionAndActiveTrueAndEndTimeAfter(sub, targetSection, now);

        if (active.isPresent()) {
            if (closeExisting) {
                AttendanceSession old = active.get();
                old.setActive(false);
                old.setEndTime(now);
                sessions.save(old);
                log.info("[DEV] Closed existing active session id={} for subject={} section={}",
                        old.getId(), sub.getCode(), targetSection);
            } else {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "An attendance session is already active for this class. Pass closeExisting=true or close it first."
                );
            }
        }

        AttendanceSession session = AttendanceSession.builder()
                .subject(sub)
                .teacher(teacher)
                .section(targetSection)
                .startTime(now.minusSeconds(1))
                .endTime(now.plusMinutes(targetDuration))
                .active(true)
                .build();

        AttendanceSession saved = sessions.save(session);

        String teacherName = (teacher.getUser() != null && teacher.getUser().getName() != null)
                ? teacher.getUser().getName()
                : "Teacher #" + teacher.getId();

        log.info("[DEV] Created DEVELOPMENT attendance session id={} for subject={} ({}) section={} teacher={} valid until {}",
                saved.getId(), sub.getCode(), sub.getName(), targetSection, teacherName, saved.getEndTime());

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Closes an attendance session by id.
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<?> closeSession(@PathVariable Long id) {
        ensureDevProfile();

        AttendanceSession session = sessions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Session not found with id " + id));

        session.setActive(false);
        session.setEndTime(LocalDateTime.now());
        sessions.save(session);

        log.info("[DEV] Closed development attendance session id={}", id);
        return ResponseEntity.ok(Map.of("message", "Session closed successfully", "id", id));
    }

    /**
     * Closes all currently active sessions (optional section filter).
     */
    @PostMapping("/close-active")
    public ResponseEntity<?> closeActive(@RequestParam(required = false) String section) {
        ensureDevProfile();

        LocalDateTime now = LocalDateTime.now();
        List<AttendanceSession> all = sessions.findAll();
        int closedCount = 0;
        for (AttendanceSession s : all) {
            if (s.isActive() && (section == null || section.isBlank() || section.equals(s.getSection()))) {
                s.setActive(false);
                s.setEndTime(now);
                sessions.save(s);
                closedCount++;
            }
        }

        log.info("[DEV] Closed {} active sessions", closedCount);
        return ResponseEntity.ok(Map.of("message", "Closed active sessions", "closedCount", closedCount));
    }

    /**
     * Queries active sessions for verification in dev mode.
     */
    @GetMapping("/active")
    public ResponseEntity<AttendanceSession> getActiveSession(@RequestParam(required = false) String section) {
        ensureDevProfile();

        LocalDateTime now = LocalDateTime.now();
        if (section != null && !section.isBlank()) {
            return sessions.findFirstBySectionAndActiveTrueAndStartTimeBeforeAndEndTimeAfterOrderByStartTimeDesc(
                    section.trim(), now, now
            ).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
        }

        return sessions.findAll().stream()
                .filter(s -> s.isActive() && s.getStartTime() != null && s.getStartTime().isBefore(now)
                        && s.getEndTime() != null && s.getEndTime().isAfter(now))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
