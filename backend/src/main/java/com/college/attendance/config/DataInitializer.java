package com.college.attendance.config;

import com.college.attendance.entity.Role;
import com.college.attendance.entity.Student;
import com.college.attendance.entity.Subject;
import com.college.attendance.entity.Teacher;
import com.college.attendance.entity.User;

import com.college.attendance.repo.StudentRepository;
import com.college.attendance.repo.SubjectRepository;
import com.college.attendance.repo.TeacherRepository;
import com.college.attendance.repo.UserRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner init(
            UserRepository users,
            StudentRepository students,
            TeacherRepository teachers,
            SubjectRepository subjects,
            PasswordEncoder enc) {

        return args -> {

            // Demo Student
            if (users.findByEmail("student@example.com").isEmpty()) {

                User s = users.save(
                        User.builder()
                                .name("Demo Student")
                                .email("student@example.com")
                                .password(enc.encode("student123"))
                                .role(Role.STUDENT)
                                .phone("9999999999")
                                .build()
                );

                students.save(
                        Student.builder()
                                .user(s)
                                .rollNumber("CSE001")
                                .course("B.Tech CSE")
                                .semester(8)
                                .section("CSE-A")
                                .build()
                );
            }

            // Demo Teacher
            if (users.findByEmail("teacher@example.com").isEmpty()) {

                User u = users.save(
                        User.builder()
                                .name("Demo Teacher")
                                .email("teacher@example.com")
                                .password(enc.encode("teacher123"))
                                .role(Role.TEACHER)
                                .build()
                );

                Teacher t = teachers.save(
                        Teacher.builder()
                                .user(u)
                                .employeeId("T001")
                                .department("CSE")
                                .build()
                );

                subjects.save(
                        Subject.builder()
                                .code("CS801")
                                .name("Data Mining")
                                .section("CSE-A")
                                .semester(8)
                                .teacher(t)
                                .build()
                );
            }

            // Demo HOD
            if (users.findByEmail("hod@example.com").isEmpty()) {

                users.save(
                        User.builder()
                                .name("Demo HOD")
                                .email("hod@example.com")
                                .password(enc.encode("hod123"))
                                .role(Role.HOD)
                                .build()
                );
            }
        };
    }
}