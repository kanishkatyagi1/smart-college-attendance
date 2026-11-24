package com.college.attendance.service;

import com.college.attendance.entity.PasswordResetOtp;
import com.college.attendance.entity.User;
import com.college.attendance.repo.PasswordResetOtpRepository;
import com.college.attendance.repo.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles the three-step password recovery flow:
 * <ol>
 *   <li>{@link #initiateReset} — validate email, send OTP.</li>
 *   <li>{@link #verifyResetOtp} — validate OTP, return a short-lived reset token.</li>
 *   <li>{@link #resetPassword} — validate token, update password, invalidate record.</li>
 * </ol>
 *
 * <h3>Isolation from registration OTPs</h3>
 * Uses a separate entity ({@link PasswordResetOtp}) and table
 * ({@code password_reset_otps}) so password-reset tokens never touch
 * the {@code email_verification_otps} table.
 *
 * <h3>Email enumeration prevention</h3>
 * {@link #initiateReset} always returns {@code true} to callers — the
 * controller sends the same generic response regardless of whether the
 * email is registered.
 *
 * <h3>Transaction safety</h3>
 * If email delivery fails the OTP record is rolled back — no usable
 * token is left in the database.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final PasswordResetOtpRepository resetOtpRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    // Nullable — only present when app.otp.dev-mode=false
    private final MailService mailService;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.otp.dev-mode:true}")
    private boolean devMode;

    @Value("${app.otp.expiration-minutes:5}")
    private long expirationMinutes;

    @Value("${app.otp.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.otp.resend-cooldown-seconds:60}")
    private long resendCooldownSeconds;

    /** Reset token is valid for 10 minutes after OTP verification. */
    private static final long RESET_TOKEN_VALID_MINUTES = 10;

    public PasswordResetService(
            PasswordResetOtpRepository resetOtpRepo,
            UserRepository userRepo,
            PasswordEncoder passwordEncoder,
            @Autowired(required = false) MailService mailService) {
        this.resetOtpRepo = resetOtpRepo;
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
    }

    // -----------------------------------------------------------------------
    // Step 1 — Initiate reset
    // -----------------------------------------------------------------------

    /**
     * Looks up the account, generates an OTP and sends it.
     *
     * <p>Always returns {@code true} regardless of whether the email is
     * registered, to prevent email enumeration.  Internally silently
     * succeeds if the account is not found.  Throws only for resend
     * cooldown violations or email delivery failures on <em>known</em>
     * accounts.</p>
     *
     * @return {@code true} always (caller must send generic response)
     */
    @Transactional
    public boolean initiateReset(String email) {

        String normalizedEmail = normalizeEmail(email);


        // After normalizing email, check if user exists
        Optional<User> userOpt = userRepo.findByEmail(normalizedEmail);
        if (userOpt.isEmpty()) {
            log.info("Password reset request for unknown email: {} – silently succeeding for enumeration protection", normalizedEmail);
            return true;
        }
        User user = userOpt.get();
        // Only active, fully-verified accounts can reset passwords
        if (!user.isEmailVerified() || !user.isActive()) {
            log.info("Password reset request for unverified/inactive account: {} – silently succeeding", normalizedEmail);
            return true;
        }


        LocalDateTime now = LocalDateTime.now();

        PasswordResetOtp existing =
                resetOtpRepo.findByEmail(normalizedEmail).orElse(null);

        if (existing != null) {
            // Enforce resend cooldown
            long elapsed = Duration.between(existing.getLastSentAt(), now).getSeconds();
            if (elapsed < resendCooldownSeconds) {
                throw new RuntimeException("Please wait before requesting another OTP");
            }
        }

        // Generate a secure 6-digit OTP
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));

        PasswordResetOtp record = PasswordResetOtp.builder()
                .email(normalizedEmail)
                .otpHash(passwordEncoder.encode(otp))
                .expiresAt(now.plusMinutes(expirationMinutes))
                .attempts(0)
                .lastSentAt(now)
                .otpVerified(false)
                .resetTokenHash(null)
                .tokenExpiresAt(null)
                .used(false)
                .build();

        if (existing != null) {
            record.setId(existing.getId());
        }

        resetOtpRepo.save(record);

        // Deliver OTP — failure rolls back via unchecked exception
        deliverOtp(normalizedEmail, otp);

        return true;
    }

    // -----------------------------------------------------------------------
    // Step 2 — Verify OTP → issue reset token
    // -----------------------------------------------------------------------

    /**
     * Validates the OTP and, if correct, marks it verified and returns a
     * one-time reset token (plain text — the hash is stored in the DB).
     *
     * @return plain-text reset token to be sent to the client, or
     *         {@code null} if the OTP is invalid/expired/exhausted
     */
    @Transactional
    public String verifyResetOtp(String email, String submittedOtp) {

        String normalizedEmail = normalizeEmail(email);

        if (submittedOtp == null || !submittedOtp.matches("\\d{6}")) {
            return null;
        }

        PasswordResetOtp record =
                resetOtpRepo.findByEmail(normalizedEmail).orElse(null);

        if (record == null || record.isOtpVerified() || record.isUsed()) {
            return null;
        }
        if (record.getAttempts() >= maxAttempts) {
            return null;
        }
        if (LocalDateTime.now().isAfter(record.getExpiresAt())) {
            resetOtpRepo.deleteByEmail(normalizedEmail);
            return null;
        }

        if (!passwordEncoder.matches(submittedOtp, record.getOtpHash())) {
            record.setAttempts(record.getAttempts() + 1);
            resetOtpRepo.save(record);
            return null;
        }

        // OTP correct — generate reset token
        String resetToken = generateResetToken();

        record.setOtpVerified(true);
        record.setOtpHash(null);           // OTP no longer usable
        record.setResetTokenHash(passwordEncoder.encode(resetToken));
        record.setTokenExpiresAt(LocalDateTime.now().plusMinutes(RESET_TOKEN_VALID_MINUTES));
        resetOtpRepo.save(record);

        return resetToken;
    }

    // -----------------------------------------------------------------------
    // Step 3 — Set new password
    // -----------------------------------------------------------------------

    /**
     * Validates the reset token and updates the user's password.
     * Invalidates the reset record after use — the token cannot be reused.
     *
     * @throws RuntimeException if the token is invalid, expired, or used
     */
    @Transactional
    public void resetPassword(String email, String resetToken, String newPassword) {

        String normalizedEmail = normalizeEmail(email);

        // Basic password validation
        validatePassword(newPassword);

        PasswordResetOtp record =
                resetOtpRepo.findByEmail(normalizedEmail).orElse(null);

        if (record == null || !record.isOtpVerified() || record.isUsed()) {
            throw new RuntimeException("Password reset request is invalid or has already been used");
        }
        if (record.getResetTokenHash() == null) {
            throw new RuntimeException("Password reset request is invalid");
        }
        if (LocalDateTime.now().isAfter(record.getTokenExpiresAt())) {
            resetOtpRepo.deleteByEmail(normalizedEmail);
            throw new RuntimeException("Reset session has expired. Please request a new OTP");
        }
        if (!passwordEncoder.matches(resetToken, record.getResetTokenHash())) {
            throw new RuntimeException("Password reset request is invalid");
        }

        // Update user password
        User user = userRepo.findByEmail(normalizedEmail)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepo.save(user);

        // Invalidate the reset record — single use
        record.setUsed(true);
        record.setResetTokenHash(null);
        resetOtpRepo.save(record);

// Begin request handling – log entry point and dev mode status
        log.info("Password reset request received for email: {} (devMode={})", normalizedEmail, devMode);
        // Existing conditional logic unchanged

    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private void deliverOtp(String email, String otp) {
        try {
            if (devMode) {
                System.out.println("[DEV RESET-OTP] Email: " + email
                        + " | OTP: " + otp
                        + " | Expires in: " + expirationMinutes + " min");
            } else {
                if (mailService == null) {
                    throw new IllegalStateException(
                            "MailService is not available. Check SMTP configuration.");
                }
                mailService.sendResetOtp(email, otp);
            }
        } catch (Exception e) {
            log.error("Password reset OTP delivery failed for {}: {}",
                    email, e.getClass().getSimpleName() + " - " + e.getMessage());
            throw new RuntimeException(
                    "Unable to send password reset email. Please try again later.");
        }
    }

    private String generateResetToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new RuntimeException("Password must be at least 8 characters");
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new RuntimeException("Email is required");
        }
        String norm = email.trim().toLowerCase(Locale.ROOT);
        if (!norm.matches("^[a-z0-9._%+-]+@gbu\\.ac\\.in$")) {
            throw new RuntimeException(
                    "Only @gbu.ac.in institutional emails are allowed");
        }
        return norm;
    }
}
