package com.college.attendance.entity;
import jakarta.persistence.*; import lombok.*; import java.time.*;
@Entity @Table(name="attendance_sessions") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceSession { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne(optional=false) private Subject subject; @ManyToOne(optional=false) private Teacher teacher; private String section; private LocalDateTime startTime; private LocalDateTime endTime; private boolean active; }
