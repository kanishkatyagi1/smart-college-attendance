package com.college.attendance.service;

import com.college.attendance.entity.*;
import com.college.attendance.repo.EmailVerificationOtpRepository;
import com.college.attendance.repo.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailOtpServiceTest {

    @Mock private EmailVerificationOtpRepository otpRepo;
    @Mock private UserRepository userRepo;
    @Mock private PasswordEncoder encoder;
    @Mock private MailService mailService;

    private EmailOtpService service;

    @BeforeEach
    void setUp() {
        // Construct with the mock MailService
        service = new EmailOtpService(otpRepo, userRepo, encoder, mailService);
        // Set default @Value fields via reflection
        setField(service, "expirationMinutes", 5L);
        setField(service, "maxAttempts", 5);
        setField(service, "resendCooldownSeconds", 60L);
    }

    // ------------------------------------------------------------------
    // Dev-mode tests (console only, no SMTP)
    // ------------------------------------------------------------------

    @Test
    void devMode_issueOtp_doesNotCallMailService() throws Exception {
        setField(service, "devMode", true);
        stubUserAndEncoder();

        service.issueOtp("student@gbu.ac.in");

        verify(mailService, never()).sendOtp(anyString(), anyString());
        verify(otpRepo, times(1)).save(any(EmailVerificationOtp.class));
    }

    // ------------------------------------------------------------------
    // SMTP-mode tests (Mailtrap / production)
    // ------------------------------------------------------------------

    @Test
    void smtpMode_issueOtp_callsMailService() throws Exception {
        setField(service, "devMode", false);
        stubUserAndEncoder();

        service.issueOtp("student@gbu.ac.in");

        verify(mailService, times(1)).sendOtp(eq("student@gbu.ac.in"), anyString());
        verify(otpRepo, times(1)).save(any(EmailVerificationOtp.class));
    }

    @Test
    void smtpMode_mailFailure_throwsAndRollsBack() throws Exception {
        setField(service, "devMode", false);
        stubUserAndEncoder();
        doThrow(new jakarta.mail.MessagingException("SMTP connection refused"))
                .when(mailService).sendOtp(anyString(), anyString());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.issueOtp("student@gbu.ac.in"));

        // The user-facing message must NOT contain SMTP details.
        assertTrue(ex.getMessage().contains("Unable to send verification email"));
        assertFalse(ex.getMessage().contains("SMTP"));
        assertFalse(ex.getMessage().contains("connection refused"));
    }

    @Test
    void smtpMode_nullMailService_throws() {
        // Simulate dev-mode=false but MailService bean missing
        EmailOtpService svcNoMail = new EmailOtpService(
                otpRepo, userRepo, encoder, null);
        setField(svcNoMail, "devMode", false);
        setField(svcNoMail, "expirationMinutes", 5L);
        setField(svcNoMail, "maxAttempts", 5);
        setField(svcNoMail, "resendCooldownSeconds", 60L);

        User user = buildUser();
        when(userRepo.findByEmail(anyString())).thenReturn(Optional.of(user));
        when(encoder.encode(anyString())).thenReturn("hashed");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> svcNoMail.issueOtp("student@gbu.ac.in"));

        assertTrue(ex.getMessage().contains("Unable to send verification email"));
    }

    // ------------------------------------------------------------------
    // GBU domain enforcement
    // ------------------------------------------------------------------

    @Test
    void rejectsNonGbuEmail() {
        setField(service, "devMode", true);

        assertThrows(RuntimeException.class,
                () -> service.issueOtp("user@gmail.com"),
                "Should reject non-GBU email");
    }

    @Test
    void rejectsLookalikeDomain() {
        setField(service, "devMode", true);

        assertThrows(RuntimeException.class,
                () -> service.issueOtp("user@fakegbu.ac.in"),
                "Should reject lookalike domain");
    }

    @Test
    void rejectsSubdomainLookalike() {
        setField(service, "devMode", true);

        assertThrows(RuntimeException.class,
                () -> service.issueOtp("user@gbu.ac.in.evil.com"),
                "Should reject subdomain lookalike");
    }

    @Test
    void acceptsValidGbuEmailCaseInsensitive() throws Exception {
        setField(service, "devMode", true);

        User user = buildUser();
        user.setEmail("student@gbu.ac.in");
        when(userRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(user));
        when(encoder.encode(anyString())).thenReturn("hashed");

        // Should not throw — mixed case input normalised to lowercase
        assertDoesNotThrow(() -> service.issueOtp("Student@GBU.AC.IN"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private User buildUser() {
        return User.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .name("Test Student")
                .password("encoded")
                .role(Role.STUDENT)
                .active(false)
                .emailVerified(false)
                .approvalStatus(ApprovalStatus.PENDING)
                .build();
    }

    private void stubUserAndEncoder() {
        User user = buildUser();
        when(userRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(user));
        when(encoder.encode(anyString())).thenReturn("hashed");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Cannot set field " + name, e);
        }
    }
}
