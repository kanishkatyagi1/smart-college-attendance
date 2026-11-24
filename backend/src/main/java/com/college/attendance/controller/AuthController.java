
package com.college.attendance.controller;

import com.college.attendance.dto.*;
import com.college.attendance.entity.Role;
import com.college.attendance.entity.User;
import com.college.attendance.repo.UserRepository;
import com.college.attendance.service.AuthService;
import com.college.attendance.service.EmailOtpService;
import com.college.attendance.service.PasswordResetService;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService auth;
    private final EmailOtpService emailOtpService;
    private final UserRepository users;
    private final PasswordResetService passwordResetService;

    public AuthController(
            AuthService auth,
            EmailOtpService emailOtpService,
            UserRepository users,
            PasswordResetService passwordResetService) {
        this.auth = auth;
        this.emailOtpService = emailOtpService;
        this.users = users;
        this.passwordResetService = passwordResetService;
    }

    /**
     * Returns a safe user-facing message.  SMTP stack traces, credentials and
     * server addresses are logged server-side only — never returned to the client.
     */
    private String safeMessage(Exception e, String fallback) {
        String msg = e.getMessage();
        // Our own RuntimeExceptions carry deliberately user-safe messages.
        // Everything else (MailException, JPA, etc.) is swallowed.
        if (e instanceof RuntimeException && msg != null && !msg.isBlank()) {
            return msg;
        }
        log.error("Unexpected error in AuthController: {}", msg, e);
        return fallback;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest r) {
        try {
            return ResponseEntity.ok(auth.login(r));
        } catch (Exception e) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", safeMessage(e, "Login failed")));
        }
    }

    @PostMapping("/register/student")
    public ResponseEntity<?> registerStudent(
            @Valid @RequestBody StudentRegistrationRequest request) {
        try {
            auth.registerStudent(request);

            return ResponseEntity.status(201).body(
                    Map.of(
                            "message", "Registration successful. Please verify your email using OTP.",
                            "status", "OTP_REQUIRED"
                    )
            );

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", safeMessage(e, "Registration failed"))
            );
        }
    }

    @PostMapping("/register/teacher")
    public ResponseEntity<?> registerTeacher(
            @Valid @RequestBody TeacherRegistrationRequest request) {
        try {
            auth.registerTeacher(request);

            return ResponseEntity.status(201).body(
                    Map.of(
                            "message", "Teacher registration submitted. Please verify your email using OTP. Your request will then await HOD or Admin approval.",
                            "status", "OTP_REQUIRED"
                    )
            );

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", safeMessage(e, "Registration failed"))
            );
        }
    }

    @PostMapping("/register/hod")
    public ResponseEntity<?> registerHod(
            @Valid @RequestBody HodRegistrationRequest request) {
        try {
            auth.registerHod(request);

            return ResponseEntity.status(201).body(
                    Map.of(
                            "message", "HOD registration submitted. Please verify your email using OTP. Your request will then await Admin approval.",
                            "status", "OTP_REQUIRED"
                    )
            );

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", safeMessage(e, "Registration failed"))
            );
        }
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(
            @Valid @RequestBody VerifyEmailOtpRequest request) {
        try {
            boolean verified = emailOtpService.verifyOtp(
                    request.getEmail(), request.getOtp());

            if (!verified) {
                return ResponseEntity.badRequest().body(
                        Map.of("message",
                                "Invalid, expired, or maximum OTP attempts exceeded.")
                );
            }

            User user = users.findByEmail(
                    request.getEmail().trim().toLowerCase(Locale.ROOT)
            ).orElseThrow(() ->
                    new RuntimeException("Account not found"));

            if (user.getRole() == Role.STUDENT) {
                return ResponseEntity.ok(
                        Map.of(
                                "message", "Email verified successfully. Your account is active. You can now log in.",
                                "status", "VERIFIED",
                                "accountStatus", "ACTIVE"
                        )
                );
            }

            if (user.getRole() == Role.TEACHER) {
                return ResponseEntity.ok(
                        Map.of(
                                "message", "Email verified successfully. Your registration is awaiting HOD or Admin approval and login credential setup.",
                                "status", "VERIFIED",
                                "accountStatus", "PENDING"
                        )
                );
            }

            if (user.getRole() == Role.HOD) {
                return ResponseEntity.ok(
                        Map.of(
                                "message", "Email verified successfully. Your registration is awaiting Admin approval and login credential setup.",
                                "status", "VERIFIED",
                                "accountStatus", "PENDING"
                        )
                );
            }

            return ResponseEntity.badRequest().body(
                    Map.of("message",
                            "OTP verification is not available for this account.")
            );

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", safeMessage(e, "Verification failed"))
            );
        }
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<?> resendOtp(
            @Valid @RequestBody EmailRequest request) {
        try {
            emailOtpService.issueOtp(request.getEmail());

            return ResponseEntity.ok(
                    Map.of("message",
                            "OTP sent. Check your registered email.")
            );

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", safeMessage(e, "Unable to resend OTP"))
            );
        }
    }

    // -----------------------------------------------------------------------
    // Password Recovery
    // -----------------------------------------------------------------------

    /**
     * Step 1: Request a password reset OTP.
     * Always returns a generic message to prevent email enumeration.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(
            @RequestBody Map<String, String> body) {
        try {
            String email = body != null ? body.get("email") : null;
            passwordResetService.initiateReset(email);
            return ResponseEntity.ok(
                    Map.of("message",
                            "If an account with that email exists, password reset instructions have been sent.")
            );
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", safeMessage(e, "Password reset request failed"))
            );
        }
    }

    /**
     * Step 2: Verify the password reset OTP.
     * Returns a short-lived reset token on success.
     */
    @PostMapping("/verify-reset-otp")
    public ResponseEntity<?> verifyResetOtp(
            @RequestBody Map<String, String> body) {
        try {
            String email = body.get("email");
            String otp = body.get("otp");

            String resetToken = passwordResetService.verifyResetOtp(email, otp);

            if (resetToken == null) {
                return ResponseEntity.badRequest().body(
                        Map.of("message",
                                "Invalid, expired, or maximum OTP attempts exceeded."));
            }

            return ResponseEntity.ok(
                    Map.of(
                            "message", "OTP verified. You may now set a new password.",
                            "resetToken", resetToken
                    )
            );

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", safeMessage(e, "OTP verification failed")));
        }
    }

    /**
     * Step 3: Set the new password using the verified reset token.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(
            @RequestBody Map<String, String> body) {
        try {
            String email = body.get("email");
            String resetToken = body.get("resetToken");
            String newPassword = body.get("newPassword");

            passwordResetService.resetPassword(email, resetToken, newPassword);

            return ResponseEntity.ok(
                    Map.of("message",
                            "Password reset successfully. Please sign in with your new password.")
            );

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", safeMessage(e, "Password reset failed")));
        }
    }
}
