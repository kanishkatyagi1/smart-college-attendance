package com.college.attendance.repo; import com.college.attendance.entity.*; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface SubmissionRepository extends JpaRepository<Submission,Long>{ List<Submission> findByStudent(Student s); }
