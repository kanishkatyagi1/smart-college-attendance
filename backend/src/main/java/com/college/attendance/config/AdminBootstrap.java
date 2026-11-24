package com.college.attendance.config;

import com.college.attendance.entity.ApprovalStatus;
import com.college.attendance.entity.Role;
import com.college.attendance.entity.User;
import com.college.attendance.repo.UserRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;

@Configuration
public class AdminBootstrap {

    @Bean
    CommandLineRunner bootstrapAdmin(
            UserRepository users,
            PasswordEncoder encoder) {

        return args -> {
            String email = System.getenv("ADMIN_EMAIL");
            String password = System.getenv("ADMIN_PASSWORD");

            boolean emailMissing = email == null || email.isBlank();
            boolean passwordMissing = password == null || password.isBlank();

            if (emailMissing && passwordMissing) {
                System.out.println("Admin bootstrap skipped: credentials not configured.");
                return;
            }

            if (emailMissing || passwordMissing || password.length() < 12) {
                throw new IllegalStateException(
                        "Admin bootstrap configuration is incomplete or password is too short."
                );
            }

            email = email.trim().toLowerCase(Locale.ROOT);

            User existing = users.findByEmail(email).orElse(null);

            if (existing != null) {
                if (existing.getRole() != Role.ADMIN) {
                    throw new IllegalStateException(
                            "Admin bootstrap email already belongs to a non-admin account."
                    );
                }

                System.out.println("Admin account already exists. No changes made.");
                return;
            }

            User admin = User.builder()
                    .name("System Administrator")
                    .email(email)
                    .password(encoder.encode(password))
                    .role(Role.ADMIN)
                    .active(true)
                    .approvalStatus(ApprovalStatus.APPROVED)
                    .emailVerified(true)
                    .build();

            users.save(admin);

            System.out.println("Admin account created successfully.");
        };
    }
}
