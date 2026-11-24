package com.college.attendance.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends OTP emails via Spring's {@link JavaMailSender}.
 *
 * <p>This bean is <b>only created</b> when {@code app.otp.dev-mode} is
 * {@code false} (or absent and the SMTP properties are supplied).  When
 * {@code app.otp.dev-mode=true} the bean is <em>not</em> instantiated, so the
 * application starts happily without any SMTP configuration.</p>
 *
 * <p>The sender address is read from the environment variable
 * {@code APP_MAIL_FROM} (property {@code app.mail.from}).  No address is
 * hardcoded.</p>
 */
@Service
@ConditionalOnProperty(name = "app.otp.dev-mode", havingValue = "false")
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;

    @Value("${app.otp.expiration-minutes:5}")
    private String expirationMinutes;

    public MailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Sends an HTML-formatted OTP email for email verification.
     *
     * @param toEmail recipient address (must be a @gbu.ac.in address – callers validate this)
     * @param otp     the 6-digit OTP code
     * @throws MessagingException if the mail cannot be composed or sent
     */
    public void sendOtp(String toEmail, String otp) throws MessagingException {
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalStateException(
                    "Sender email (app.mail.from / APP_MAIL_FROM) is not configured");
        }

        String subject = "Smart Attendance \u2013 Your Verification OTP";

        String htmlBody = "<!DOCTYPE html>"
                + "<html><body style=\"font-family:Arial,Helvetica,sans-serif; "
                + "max-width:600px; margin:0 auto; padding:20px;\">"
                + "<div style=\"border-bottom:3px solid #1a73e8; padding-bottom:10px;\">"
                + "<h2 style=\"color:#1a73e8; margin:0;\">Smart College Attendance System</h2>"
                + "<p style=\"color:#555; margin:4px 0 0 0;\">Gautam Buddha University</p>"
                + "</div>"
                + "<p style=\"margin-top:20px;\">Dear Student,</p>"
                + "<p>Your one-time password (OTP) for email verification is:</p>"
                + "<div style=\"background:#f4f4f4; border-radius:8px; padding:20px; "
                + "text-align:center; margin:20px 0;\">"
                + "<span style=\"font-size:2em; font-weight:bold; letter-spacing:8px; "
                + "color:#1a73e8;\">" + escapeHtml(otp) + "</span>"
                + "</div>"
                + "<p>This OTP is valid for <b>" + escapeHtml(expirationMinutes)
                + " minutes</b>.</p>"
                + "<p style=\"color:#d32f2f; font-weight:bold;\">"
                + "\u26a0 Do not share this OTP with anyone. "
                + "The university will never ask for your OTP.</p>"
                + "<hr style=\"border:none; border-top:1px solid #ddd; margin:20px 0;\"/>"
                + "<p style=\"color:#888; font-size:0.85em;\">"
                + "If you did not request this, please ignore this email.</p>"
                + "<p style=\"color:#888; font-size:0.85em;\">Regards,<br/>"
                + "GBU Attendance System</p>"
                + "</body></html>";

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromAddress);
        helper.setTo(toEmail);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);

        mailSender.send(message);
        log.info("OTP email sent to {} via SMTP", toEmail);
    }

    /**
     * Sends an HTML-formatted OTP email for password reset.
     *
     * @param toEmail recipient address (must be a @gbu.ac.in address – callers validate this)
     * @param otp     the 6-digit OTP code
     * @throws MessagingException if the mail cannot be composed or sent
     */
    public void sendResetOtp(String toEmail, String otp) throws MessagingException {
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalStateException(
                    "Sender email (app.mail.from / APP_MAIL_FROM) is not configured");
        }

        String subject = "Smart Attendance \u2013 Password Reset OTP";

        String htmlBody = "<!DOCTYPE html>"
                + "<html><body style=\"font-family:Arial,Helvetica,sans-serif; "
                + "max-width:600px; margin:0 auto; padding:20px;\">"
                + "<div style=\"border-bottom:3px solid #d32f2f; padding-bottom:10px;\">"
                + "<h2 style=\"color:#d32f2f; margin:0;\">Password Reset Request</h2>"
                + "<p style=\"color:#555; margin:4px 0 0 0;\">Smart College Attendance System &mdash; Gautam Buddha University</p>"
                + "</div>"
                + "<p style=\"margin-top:20px;\">Dear Student,</p>"
                + "<p>We received a request to reset the password for your GBU account. "
                + "Your one-time password (OTP) is:</p>"
                + "<div style=\"background:#fdf3f3; border-radius:8px; padding:20px; "
                + "text-align:center; margin:20px 0;\">"
                + "<span style=\"font-size:2em; font-weight:bold; letter-spacing:8px; "
                + "color:#d32f2f;\">" + escapeHtml(otp) + "</span>"
                + "</div>"
                + "<p>This OTP is valid for <b>" + escapeHtml(expirationMinutes)
                + " minutes</b>.</p>"
                + "<p style=\"color:#d32f2f; font-weight:bold;\">"
                + "&#9888; Do not share this OTP with anyone. "
                + "If you did not request a password reset, please ignore this email and your password will remain unchanged.</p>"
                + "<hr style=\"border:none; border-top:1px solid #ddd; margin:20px 0;\"/>"
                + "<p style=\"color:#888; font-size:0.85em;\">Regards,<br/>"
                + "GBU Attendance System</p>"
                + "</body></html>";

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromAddress);
        helper.setTo(toEmail);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);

        mailSender.send(message);
        log.info("Password reset OTP email sent to {} via SMTP", toEmail);
    }

    /** Minimal HTML escaping for template values. */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
