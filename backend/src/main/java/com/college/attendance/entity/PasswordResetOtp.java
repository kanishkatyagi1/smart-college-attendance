package com.college.attendance.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Stores a BCrypt-hashed password-reset OTP.
 *
 * <p>Completely separate from {@link EmailVerificationOtp} so that
 * password-reset tokens can never accidentally invalidate or reuse a
 * registration OTP.</p>
 *
 * <p>Once verified, {@code verified = true} and a short-lived
 * {@code resetToken} is stored (also hashed).  The token must be
 * presented alongside the new password to complete the reset.
 * This prevents an attacker from changing the password even if
 * they intercept the "OTP verified" response.</p>
 */
@Entity
@Table(name = "password_reset_otps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalised (lowercase) @gbu.ac.in email address. */
    @Column(nullable = false, unique = true, length = 254)
    private String email;

    /** BCrypt hash of the 6-digit OTP.  Null once OTP is verified. */
    @Column(length = 100)
    private String otpHash;

    /** When the OTP expires. */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** Number of incorrect OTP submissions. */
    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    /** When the most recent OTP was sent (used for resend cooldown). */
    @Column(nullable = false)
    private LocalDateTime lastSentAt;

    /**
     * True after a correct OTP is submitted.
     * Only a verified record may be used to reset the password.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean otpVerified = false;

    /**
     * BCrypt hash of the single-use reset token issued after OTP
     * verification.  Null until the OTP is verified.
     */
    @Column(length = 100)
    private String resetTokenHash;

    /**
     * When the reset token expires.  Short window (10 minutes) after
     * OTP verification.  Null until the OTP is verified.
     */
    private LocalDateTime tokenExpiresAt;

    /** True after the password has been successfully changed. */
    @Builder.Default
    @Column(nullable = false)
    private boolean used = false;
}
