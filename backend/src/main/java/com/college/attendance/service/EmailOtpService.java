package com.college.attendance.service;

import com.college.attendance.entity.*;
import com.college.attendance.repo.EmailVerificationOtpRepository;
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
import java.util.Locale;

/**
 * Handles OTP generation, secure storage, and delivery for email verification.
 *
 * <h3>Transaction safety</h3>
 * {@link #issueOtp} is {@code @Transactional}.  The OTP record is persisted
 * <em>inside</em> the same transaction as the user/student record created by
 * {@link AuthService#registerStudent}.  If email delivery fails we throw an
 * unchecked {@link RuntimeException}, which causes Spring to roll back the
 * <em>entire</em> transaction — no orphaned OTP or user records survive.
 *
 * <h3>Dev-mode vs. SMTP mode</h3>
 * <ul>
 *   <li>{@code app.otp.dev-mode=true} — OTP printed to console only;
 *       no SMTP required; app starts without mail config.</li>
 *   <li>{@code app.otp.dev-mode=false} — OTP sent via {@link MailService};
 *       SMTP env vars must be set; OTP is <em>never</em> logged.</li>
 * </ul>
 */
@Service
public class EmailOtpService {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpService.class);

    private final EmailVerificationOtpRepository otpRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // Nullable — only present when app.otp.dev-mode=false (see MailService @ConditionalOnProperty)
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

    /**
     * Constructor.  {@code mailService} is {@code @Autowired(required=false)}
     * because the {@link MailService} bean only exists when
     * {@code app.otp.dev-mode=false}.
     */
    public EmailOtpService(
            EmailVerificationOtpRepository otpRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Autowired(required = false) MailService mailService) {
        this.otpRepository = otpRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Issues a new 6-digit OTP for the given email address.
     *
     * <ol>
     *   <li>Validates the email domain (@gbu.ac.in) — defence-in-depth.</li>
     *   <li>Enforces resend cooldown (default 60 s).</li>
     *   <li>Persists the OTP hash within the current transaction.</li>
     *   <li>Delivers the OTP (console in dev-mode, SMTP otherwise).</li>
     *   <li>If delivery fails → RuntimeException → full rollback.</li>
     * </ol>
     */
    @Transactional
    public void issueOtp(String email) {

        String normalizedEmail = normalizeEmail(email);

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        if (user.isEmailVerified()) {
            throw new RuntimeException("Email is already verified");
        }
        if (user.getApprovalStatus() == ApprovalStatus.REJECTED) {
            throw new RuntimeException("Account registration was rejected");
        }

        LocalDateTime now = LocalDateTime.now();

        EmailVerificationOtp existing =
                otpRepository.findByEmail(normalizedEmail).orElse(null);

        if (existing != null) {
            long elapsed = Duration.between(existing.getLastSentAt(), now).getSeconds();
            if (elapsed < resendCooldownSeconds) {
                throw new RuntimeException("Please wait before requesting another OTP");
            }
        }

        // Generate a secure random 6-digit OTP.
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));

        // Store as BCrypt hash — plaintext is never persisted.
        EmailVerificationOtp record = EmailVerificationOtp.builder()
                .email(normalizedEmail)
                .otpHash(passwordEncoder.encode(otp))
                .expiresAt(now.plusMinutes(expirationMinutes))
                .attempts(0)
                .lastSentAt(now)
                .verified(false)
                .build();

        if (existing != null) {
            record.setId(existing.getId());
        }

        // Persist inside the transaction.  A failure below will roll
        // this back along with everything in the calling transaction.
        otpRepository.save(record);

        // ---- DELIVER THE OTP ------------------------------------------------
        try {
            if (devMode) {
                // Console-only development mode.  OTP is logged to stdout.
                // SMTP is NOT called.  No MailService bean is required.
                System.out.println("[DEV OTP] Email: " + normalizedEmail
                        + " | OTP: " + otp
                        + " | Expires in: " + expirationMinutes + " min");
            } else {
                // SMTP mode (Mailtrap in dev, real provider in prod).
                // OTP is NOT printed to any log.
                if (mailService == null) {
                    throw new IllegalStateException(
                            "MailService is not available. Check SMTP configuration.");
                }
                mailService.sendOtp(normalizedEmail, otp);
            }
        } catch (Exception e) {
            // Log technical details server-side only (never the OTP).
            log.error("OTP email delivery failed for {}: {}", normalizedEmail,
                    e.getClass().getSimpleName() + " - " + e.getMessage());
            // Throw unchecked → Spring rolls back the entire @Transactional.
            // User-facing message is deliberately vague.
            throw new RuntimeException(
                    "Unable to send verification email. Please try again later.");
        }
        // ---------------------------------------------------------------------
    }

    /**
     * Verifies a submitted OTP against the stored hash.
     * On success: marks the user as verified and (for students) approved+active.
     */
    @Transactional
    public boolean verifyOtp(String email, String submittedOtp) {

        String normalizedEmail = normalizeEmail(email);

        if (submittedOtp == null || !submittedOtp.matches("\\d{6}")) {
            return false;
        }

        EmailVerificationOtp record =
                otpRepository.findByEmail(normalizedEmail).orElse(null);

        if (record == null || record.isVerified()) {
            return false;
        }
        if (record.getAttempts() >= maxAttempts) {
            return false;
        }
        if (LocalDateTime.now().isAfter(record.getExpiresAt())) {
            otpRepository.deleteByEmail(normalizedEmail);
            return false;
        }

        if (!passwordEncoder.matches(submittedOtp, record.getOtpHash())) {
            record.setAttempts(record.getAttempts() + 1);
            otpRepository.save(record);
            return false;
        }

        User user = userRepository.findByEmail(normalizedEmail).orElse(null);
        if (user == null || user.getApprovalStatus() == ApprovalStatus.REJECTED) {
            return false;
        }

        user.setEmailVerified(true);

        if (user.getRole() == Role.STUDENT) {
            user.setApprovalStatus(ApprovalStatus.APPROVED);
            user.setActive(true);
        } else if (user.getRole() == Role.TEACHER || user.getRole() == Role.HOD) {
            user.setApprovalStatus(ApprovalStatus.PENDING);
            user.setActive(false);
        } else {
            return false;
        }

        userRepository.save(user);
        otpRepository.deleteByEmail(normalizedEmail);
        return true;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Normalises and validates the email address.
     * Enforces exact {@code @gbu.ac.in} suffix (case-insensitive).
     * Rejects look-alike domains such as {@code @fakegbu.ac.in} or
     * {@code @gbu.ac.in.evil.com}.
     */
    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new RuntimeException("Email is required");
        }
        String norm = email.trim().toLowerCase(Locale.ROOT);
        if (!norm.endsWith("@gbu.ac.in")) {
            throw new RuntimeException(
                    "Only @gbu.ac.in institutional emails are allowed");
        }
        return norm;
    }
}
