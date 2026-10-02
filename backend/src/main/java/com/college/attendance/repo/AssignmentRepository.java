package com.college.attendance.repo; import com.college.attendance.entity.*; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface AssignmentRepository extends JpaRepository<Assignment,Long>{ List<Assignment> findBySubjectIn(List<Subject> subjects); }
