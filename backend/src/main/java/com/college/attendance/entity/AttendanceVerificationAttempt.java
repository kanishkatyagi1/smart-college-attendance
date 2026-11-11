package com.college.attendance.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "attendance_verification_attempts", indexes = {
        @Index(name = "idx_verification_student_session", columnList = "student_id,session_id"),
        @Index(name = "idx_verification_expires", columnList = "expiresAt")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceVerificationAttempt {
    @Id
    private String id;
    @ManyToOne(optional = false) @JoinColumn(name = "student_id")
    private Student student;
    @ManyToOne(optional = false) @JoinColumn(name = "session_id")
    private AttendanceSession session;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime completedAt;
    @Enumerated(EnumType.STRING)
    private VerificationAttemptStatus status;
    @Version
    private long version;
}
