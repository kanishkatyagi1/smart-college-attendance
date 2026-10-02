package com.college.attendance.entity;
import jakarta.persistence.*; import lombok.*;
@Entity @Table(name="students") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Student { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @OneToOne(optional=false) private User user; @Column(unique=true) private String rollNumber; private String course; private int semester; private String section; private String faceImagePath; }
