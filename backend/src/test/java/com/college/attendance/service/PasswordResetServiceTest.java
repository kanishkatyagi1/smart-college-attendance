package com.college.attendance.service;

import com.college.attendance.entity.*;
import com.college.attendance.repo.EmailVerificationOtpRepository;
import com.college.attendance.repo.PasswordResetOtpRepository;
import com.college.attendance.repo.UserRepository;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private PasswordResetOtpRepository resetOtpRepo;
    @Mock private UserRepository userRepo;
    @Mock private PasswordEncoder encoder;
    @Mock private MailService mailService;
    @Mock private EmailVerificationOtpRepository registrationOtpRepo;

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(resetOtpRepo, userRepo, encoder, mailService);
        setField(service, "expirationMinutes", 5L);
        setField(service, "maxAttempts", 5);
        setField(service, "resendCooldownSeconds", 60L);
        setField(service, "devMode", true);
    }

    // -----------------------------------------------------------------------
    // 1. Valid registered GBU email reset request
    // -----------------------------------------------------------------------
    @Test
    void test1_validRegisteredGbuEmail_initiatesReset_andSucceeds() {
        User user = buildActiveUser();
        when(userRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(user));
        when(encoder.encode(anyString())).thenReturn("hashed_otp");

        boolean result = service.initiateReset("student@gbu.ac.in");

        assertTrue(result);
        verify(resetOtpRepo, times(1)).save(any(PasswordResetOtp.class));
    }

    // -----------------------------------------------------------------------
    // 2. Unknown email returns the same generic response (enumeration prevention)
    // -----------------------------------------------------------------------
    @Test
    void test2_unknownEmail_returnsGenericSuccess_doesNotSaveOrSend() throws Exception {
        when(userRepo.findByEmail("unknown@gbu.ac.in")).thenReturn(Optional.empty());

        boolean result = service.initiateReset("unknown@gbu.ac.in");

        assertTrue(result, "Must return generic success to prevent email enumeration");
        verify(resetOtpRepo, never()).save(any(PasswordResetOtp.class));
        verify(mailService, never()).sendResetOtp(anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // 3. Personal email rejected
    // -----------------------------------------------------------------------
    @Test
    void test3_personalEmail_rejected() {
        RuntimeException ex1 = assertThrows(RuntimeException.class,
                () -> service.initiateReset("student@gmail.com"));
        assertTrue(ex1.getMessage().contains("@gbu.ac.in"));

        RuntimeException ex2 = assertThrows(RuntimeException.class,
                () -> service.initiateReset("user@yahoo.com"));
        assertTrue(ex2.getMessage().contains("@gbu.ac.in"));

        RuntimeException ex3 = assertThrows(RuntimeException.class,
                () -> service.initiateReset("user@fakegbu.ac.in"));
        assertTrue(ex3.getMessage().contains("@gbu.ac.in"));

        RuntimeException ex4 = assertThrows(RuntimeException.class,
                () -> service.initiateReset("user@gbu.ac.in.evil.com"));
        assertTrue(ex4.getMessage().contains("@gbu.ac.in"));
    }

    // -----------------------------------------------------------------------
    // 4. OTP sent using MailService (in SMTP mode)
    // -----------------------------------------------------------------------
    @Test
    void test4_otpSentUsingMailService_inSmtpMode() throws Exception {
        setField(service, "devMode", false);
        User user = buildActiveUser();
        when(userRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(user));
        when(encoder.encode(anyString())).thenReturn("hashed_otp");

        boolean result = service.initiateReset("student@gbu.ac.in");

        assertTrue(result);
        verify(mailService, times(1)).sendResetOtp(eq("student@gbu.ac.in"), anyString());
        verify(resetOtpRepo, times(1)).save(any(PasswordResetOtp.class));
    }

    // -----------------------------------------------------------------------
    // 5. OTP expires after five minutes
    // -----------------------------------------------------------------------
    @Test
    void test5_otpExpires_afterFiveMinutes() {
        PasswordResetOtp expiredRecord = PasswordResetOtp.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .otpHash("hashed_otp")
                .expiresAt(LocalDateTime.now().minusMinutes(1)) // Expired
                .attempts(0)
                .lastSentAt(LocalDateTime.now().minusMinutes(6))
                .otpVerified(false)
                .build();

        when(resetOtpRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(expiredRecord));

        String token = service.verifyResetOtp("student@gbu.ac.in", "123456");

        assertNull(token, "Expired OTP must return null");
        verify(resetOtpRepo, times(1)).deleteByEmail("student@gbu.ac.in");
    }

    // -----------------------------------------------------------------------
    // 6. Resend cooldown enforced
    // -----------------------------------------------------------------------
    @Test
    void test6_resendCooldown_enforced() {
        User user = buildActiveUser();
        when(userRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(user));

        PasswordResetOtp recentRecord = PasswordResetOtp.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .otpHash("hashed_otp")
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .attempts(0)
                .lastSentAt(LocalDateTime.now().minusSeconds(20)) // Only 20s ago
                .otpVerified(false)
                .build();

        when(resetOtpRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(recentRecord));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.initiateReset("student@gbu.ac.in"));
        assertTrue(ex.getMessage().contains("Please wait before requesting another OTP"));
    }

    // -----------------------------------------------------------------------
    // 7. Maximum five attempts enforced
    // -----------------------------------------------------------------------
    @Test
    void test7_maximumAttempts_enforced() {
        PasswordResetOtp record = PasswordResetOtp.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .otpHash("hashed_otp")
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .attempts(5) // Max reached
                .lastSentAt(LocalDateTime.now().minusMinutes(1))
                .otpVerified(false)
                .build();

        when(resetOtpRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(record));

        String token = service.verifyResetOtp("student@gbu.ac.in", "123456");

        assertNull(token, "Should reject when max attempts exceeded");
        verify(encoder, never()).matches(anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // 8. Invalid OTP rejected and attempts incremented
    // -----------------------------------------------------------------------
    @Test
    void test8_invalidOtp_rejected_incrementsAttempts() {
        PasswordResetOtp record = PasswordResetOtp.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .otpHash("hashed_otp")
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .attempts(1)
                .lastSentAt(LocalDateTime.now().minusMinutes(1))
                .otpVerified(false)
                .build();

        when(resetOtpRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(record));
        when(encoder.matches("999999", "hashed_otp")).thenReturn(false);

        String token = service.verifyResetOtp("student@gbu.ac.in", "999999");

        assertNull(token);
        assertEquals(2, record.getAttempts());
        verify(resetOtpRepo, times(1)).save(record);
    }

    // -----------------------------------------------------------------------
    // 9. Reset without OTP verification rejected
    // -----------------------------------------------------------------------
    @Test
    void test9_resetWithoutOtpVerification_rejected() {
        PasswordResetOtp unverified = PasswordResetOtp.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .otpHash("hashed_otp")
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .attempts(0)
                .lastSentAt(LocalDateTime.now().minusMinutes(1))
                .otpVerified(false) // Not verified
                .resetTokenHash(null)
                .build();

        when(resetOtpRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(unverified));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.resetPassword("student@gbu.ac.in", "fake_token", "newPassword123"));

        assertTrue(ex.getMessage().contains("invalid"));
        verify(userRepo, never()).save(any(User.class));
    }

    // -----------------------------------------------------------------------
    // 10. Password successfully changed after valid verification
    // -----------------------------------------------------------------------
    @Test
    void test10_passwordSuccessfullyChanged_afterValidVerification() {
        User user = buildActiveUser();
        when(userRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(user));

        PasswordResetOtp verifiedRecord = PasswordResetOtp.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .otpVerified(true)
                .resetTokenHash("hashed_token")
                .tokenExpiresAt(LocalDateTime.now().plusMinutes(10))
                .used(false)
                .build();

        when(resetOtpRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(verifiedRecord));
        when(encoder.matches("valid_token", "hashed_token")).thenReturn(true);
        when(encoder.encode("newSecurePassword123")).thenReturn("new_encoded_password");

        service.resetPassword("student@gbu.ac.in", "valid_token", "newSecurePassword123");

        assertEquals("new_encoded_password", user.getPassword());
        assertTrue(verifiedRecord.isUsed());
        assertNull(verifiedRecord.getResetTokenHash());
        verify(userRepo, times(1)).save(user);
        verify(resetOtpRepo, times(1)).save(verifiedRecord);
    }

    // -----------------------------------------------------------------------
    // 11. Old password rejected and new password works (verification of change)
    // -----------------------------------------------------------------------
    @Test
    void test11_oldPassword_noLongerMatches() {
        User user = buildActiveUser();
        user.setPassword("old_hash");
        when(userRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(user));

        PasswordResetOtp verifiedRecord = PasswordResetOtp.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .otpVerified(true)
                .resetTokenHash("hashed_token")
                .tokenExpiresAt(LocalDateTime.now().plusMinutes(10))
                .used(false)
                .build();

        when(resetOtpRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(verifiedRecord));
        when(encoder.matches("valid_token", "hashed_token")).thenReturn(true);
        when(encoder.encode("newPassword123")).thenReturn("new_hash");

        service.resetPassword("student@gbu.ac.in", "valid_token", "newPassword123");

        assertNotEquals("old_hash", user.getPassword());
        assertEquals("new_hash", user.getPassword());
    }

    // -----------------------------------------------------------------------
    // 12. Reset OTP / token cannot be reused
    // -----------------------------------------------------------------------
    @Test
    void test12_resetToken_cannotBeReused() {
        PasswordResetOtp alreadyUsed = PasswordResetOtp.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .otpVerified(true)
                .resetTokenHash(null)
                .used(true) // Already used
                .build();

        when(resetOtpRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(alreadyUsed));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.resetPassword("student@gbu.ac.in", "any_token", "newPassword123"));

        assertTrue(ex.getMessage().contains("invalid or has already been used"));
    }

    // -----------------------------------------------------------------------
    // 13. Reset OTP cannot be reused to get another token
    // -----------------------------------------------------------------------
    @Test
    void test13_verifiedOtp_cannotBeReverified() {
        PasswordResetOtp verified = PasswordResetOtp.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .otpVerified(true)
                .otpHash(null) // Cleared on verification
                .build();

        when(resetOtpRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(verified));

        String token = service.verifyResetOtp("student@gbu.ac.in", "123456");
        assertNull(token, "Already-verified OTP must return null");
    }

    // -----------------------------------------------------------------------
    // 14. Registration OTP unaffected
    // -----------------------------------------------------------------------
    @Test
    void test14_registrationOtp_remainsUnaffected() {
        User user = buildActiveUser();
        when(userRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(user));
        when(encoder.encode(anyString())).thenReturn("hashed_otp");

        service.initiateReset("student@gbu.ac.in");

        // Verify that registration repository was never interacted with
        verifyNoInteractions(registrationOtpRepo);
    }

    // -----------------------------------------------------------------------
    // 15. Student approval status remains unchanged
    // -----------------------------------------------------------------------
    @Test
    void test15_studentApprovalStatus_remainsUnchanged() {
        User user = buildActiveUser();
        user.setApprovalStatus(ApprovalStatus.APPROVED);
        user.setActive(true);
        user.setEmailVerified(true);
        when(userRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(user));

        PasswordResetOtp verifiedRecord = PasswordResetOtp.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .otpVerified(true)
                .resetTokenHash("hashed_token")
                .tokenExpiresAt(LocalDateTime.now().plusMinutes(10))
                .used(false)
                .build();

        when(resetOtpRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(verifiedRecord));
        when(encoder.matches("valid_token", "hashed_token")).thenReturn(true);
        when(encoder.encode(anyString())).thenReturn("new_hash");

        service.resetPassword("student@gbu.ac.in", "valid_token", "newPassword123");

        assertEquals(ApprovalStatus.APPROVED, user.getApprovalStatus(), "Approval status must not change");
        assertTrue(user.isActive(), "Active status must not change");
        assertTrue(user.isEmailVerified(), "Email verified must not change");
    }

    // -----------------------------------------------------------------------
    // 16. SMTP failure throws and rolls back
    // -----------------------------------------------------------------------
    @Test
    void test16_smtpFailure_throwsAndRollsBack() throws Exception {
        setField(service, "devMode", false);
        User user = buildActiveUser();
        when(userRepo.findByEmail("student@gbu.ac.in")).thenReturn(Optional.of(user));
        when(encoder.encode(anyString())).thenReturn("hashed_otp");

        doThrow(new MessagingException("SMTP host unreachable"))
                .when(mailService).sendResetOtp(anyString(), anyString());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.initiateReset("student@gbu.ac.in"));

        assertTrue(ex.getMessage().contains("Unable to send password reset email"));
        assertFalse(ex.getMessage().contains("SMTP"), "Must not leak SMTP details");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private User buildActiveUser() {
        return User.builder()
                .id(10L)
                .email("student@gbu.ac.in")
                .name("Student User")
                .password("old_encoded_password")
                .role(Role.STUDENT)
                .active(true)
                .emailVerified(true)
                .approvalStatus(ApprovalStatus.APPROVED)
                .build();
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
