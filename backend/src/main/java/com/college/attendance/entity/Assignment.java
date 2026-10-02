package com.college.attendance.entity;
import jakarta.persistence.*; import lombok.*; import java.time.*;
@Entity @Table(name="assignments") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Assignment { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; private String title; @Column(length=2000) private String description; private LocalDate dueDate; @ManyToOne private Subject subject; @ManyToOne private Teacher teacher; }
