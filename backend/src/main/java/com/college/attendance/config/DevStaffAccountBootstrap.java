
package com.college.attendance.config;

import com.college.attendance.entity.ApprovalStatus;
import com.college.attendance.entity.Role;
import com.college.attendance.entity.User;
import com.college.attendance.repo.UserRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@Profile("dev")
public class DevStaffAccountBootstrap {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    CommandLineRunner bootstrapDemoStaff(
            UserRepository users,
            PasswordEncoder passwordEncoder) {

        return args -> {
            activateDemoAccount(
                    users,
                    passwordEncoder,
                    "teacher@example.com",
                    Role.TEACHER,
                    "DEMO_TEACHER_PASSWORD"
            );

            activateDemoAccount(
                    users,
                    passwordEncoder,
                    "hod@example.com",
                    Role.HOD,
                    "DEMO_HOD_PASSWORD"
            );

            System.out.println(
                    "Development demo staff accounts initialized."
            );
        };
    }

    private void activateDemoAccount(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            String email,
            Role expectedRole,
            String passwordVariable) {

        User user = users.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException(
                        "Demo account not found: " + email
                ));

        if (user.getRole() != expectedRole) {
            throw new IllegalStateException(
                    "Unexpected role for demo account: " + email
            );
        }

        String rawPassword = System.getenv(passwordVariable);

        if (rawPassword == null || rawPassword.length() < 12) {
            throw new IllegalStateException(
                    passwordVariable
                    + " must be configured with at least 12 characters."
            );
        }

        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setEmailVerified(true);
        user.setApprovalStatus(ApprovalStatus.APPROVED);
        user.setActive(true);

        users.save(user);
    }
}
