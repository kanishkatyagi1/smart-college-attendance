package com.college.attendance.mobile.model;

public class ResetPasswordRequest {
    public String email;
    public String resetToken;
    public String newPassword;
    public String confirmPassword;

    public ResetPasswordRequest(String email, String resetToken, String newPassword, String confirmPassword) {
        this.email = email;
        this.resetToken = resetToken;
        this.newPassword = newPassword;
        this.confirmPassword = confirmPassword;
    }
}
