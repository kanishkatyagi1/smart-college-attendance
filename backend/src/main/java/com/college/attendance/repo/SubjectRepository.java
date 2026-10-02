package com.college.attendance.repo; import com.college.attendance.entity.*; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface SubjectRepository extends JpaRepository<Subject,Long>{ List<Subject> findByTeacher(Teacher t); List<Subject> findBySection(String s); }
