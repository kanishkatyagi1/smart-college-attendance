package com.college.attendance.repo; import com.college.attendance.entity.*; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface TeacherRepository extends JpaRepository<Teacher,Long>{ Optional<Teacher> findByUser(User u); }
