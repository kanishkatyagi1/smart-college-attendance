package com.college.attendance.entity;
import jakarta.persistence.*; import lombok.*; import java.time.*;
@Entity @Table(name="feedback_complaints") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FeedbackComplaint { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne private User raisedBy; private String type; private String subject; @Column(length=2000) private String message; private String status; private String resolution; private LocalDateTime createdAt; private LocalDateTime resolvedAt; }
