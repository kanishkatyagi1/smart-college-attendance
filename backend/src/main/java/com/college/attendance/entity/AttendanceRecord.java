package com.college.attendance.entity;
import jakarta.persistence.*; import lombok.*; import java.time.*;
@Entity @Table(name="attendance_records", uniqueConstraints=@UniqueConstraint(columnNames={"session_id","student_id"})) @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceRecord { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne(optional=false) @JoinColumn(name="session_id") private AttendanceSession session; @ManyToOne(optional=false) @JoinColumn(name="student_id") private Student student; private LocalDateTime markedAt; private String status; private String verificationMethod; }
