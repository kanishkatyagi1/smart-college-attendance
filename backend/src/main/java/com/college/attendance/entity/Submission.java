package com.college.attendance.entity;
import jakarta.persistence.*; import lombok.*; import java.time.*;
@Entity @Table(name="submissions") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Submission { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne private Assignment assignment; @ManyToOne private Student student; private String fileUrl; private Double marks; @Column(length=1000) private String remarks; private LocalDateTime submittedAt; }
