package com.college.attendance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name="users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;

    @Column(unique=true, nullable=false)
    private String email;

    @JsonIgnore
    @Column(nullable=false)
    private String password;

    @Column(nullable=false)
    private String name;

    @Enumerated(EnumType.STRING)
    private Role role;

    private String phone;

    private boolean active = true;
}