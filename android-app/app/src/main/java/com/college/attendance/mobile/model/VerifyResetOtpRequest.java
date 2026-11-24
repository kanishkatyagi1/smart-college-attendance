package com.college.attendance.mobile.model;

public class VerifyResetOtpRequest {
    public String email;
    public String otp;

    public VerifyResetOtpRequest(String email, String otp) {
        this.email = email;
        this.otp = otp;
    }
}
