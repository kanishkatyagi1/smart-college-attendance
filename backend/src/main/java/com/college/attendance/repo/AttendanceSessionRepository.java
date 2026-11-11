package com.college.attendance.repo;

import com.college.attendance.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.*;

public interface AttendanceSessionRepository
        extends JpaRepository<AttendanceSession, Long> {

    List<AttendanceSession> findByTeacherOrderByStartTimeDesc(Teacher t);

    Optional<AttendanceSession> findFirstBySectionAndActiveTrue(String section);

    Optional<AttendanceSession>
        findFirstBySubjectAndSectionAndActiveTrueAndEndTimeAfter(
            Subject subject,
            String section,
            LocalDateTime now
        );
}
