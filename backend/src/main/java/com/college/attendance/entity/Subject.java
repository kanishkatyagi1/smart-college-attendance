package com.college.attendance.entity;
import jakarta.persistence.*; import lombok.*;
@Entity @Table(name="subjects") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Subject { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; private String code; private String name; private String section; private int semester; @ManyToOne private Teacher teacher; }
