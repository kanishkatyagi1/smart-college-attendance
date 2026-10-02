package com.college.attendance.repo; import com.college.attendance.entity.*; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface StudentRepository extends JpaRepository<Student,Long>{ Optional<Student> findByUser(User u); Optional<Student> findByRollNumber(String r); List<Student> findBySection(String s); }
