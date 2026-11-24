package com.college.attendance.repo;

import com.college.attendance.entity.PasswordResetOtp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetOtpRepository
        extends JpaRepository<PasswordResetOtp, Long> {

    Optional<PasswordResetOtp> findByEmail(String email);

    void deleteByEmail(String email);
}
