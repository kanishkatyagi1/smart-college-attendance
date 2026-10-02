package com.college.attendance.entity;
import jakarta.persistence.*; import lombok.*;
@Entity @Table(name="teachers") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Teacher { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @OneToOne(optional=false) private User user; @Column(unique=true) private String employeeId; private String department; }
