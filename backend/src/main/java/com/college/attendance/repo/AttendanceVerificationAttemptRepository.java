package com.college.attendance.repo;

import com.college.attendance.entity.AttendanceVerificationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface AttendanceVerificationAttemptRepository extends JpaRepository<AttendanceVerificationAttempt, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AttendanceVerificationAttempt a where a.id = :id")
    Optional<AttendanceVerificationAttempt> findWithLockById(@Param("id") String id);
}
