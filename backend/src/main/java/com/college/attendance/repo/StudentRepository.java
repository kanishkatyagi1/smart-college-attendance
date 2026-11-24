package com.college.attendance.repo;

import com.college.attendance.entity.Student;
import com.college.attendance.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {
    Optional<Student> findByUser(User u);
    Optional<Student> findByRollNumber(String r);
    List<Student> findBySection(String s);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Student s where s.id = :id")
    Optional<Student> findWithLockById(@Param("id") Long id);
}
