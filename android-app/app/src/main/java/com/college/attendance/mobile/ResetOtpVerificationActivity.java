package com.college.attendance.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.college.attendance.mobile.api.ApiClient;
import com.college.attendance.mobile.model.ForgotPasswordRequest;
import com.college.attendance.mobile.model.VerifyResetOtpRequest;

import org.json.JSONObject;

import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ResetOtpVerificationActivity extends AppCompatActivity {

    private EditText otpInput;
    private TextView otpStatus;
    private Button verifyButton, resendButton;
    private String email;
    private CountDownTimer resendTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reset_otp_verification);

        otpInput = findViewById(R.id.resetOtpInput);
        otpStatus = findViewById(R.id.resetOtpStatus);
        verifyButton = findViewById(R.id.verifyResetOtpButton);
        resendButton = findViewById(R.id.resendResetOtpButton);

        email = getIntent().getStringExtra("email");

        if (email == null || email.trim().isEmpty()) {
            otpStatus.setTextColor(0xFFD32F2F);
            otpStatus.setText("Email address is missing.");
            verifyButton.setEnabled(false);
            resendButton.setEnabled(false);
            return;
        }

        TextView emailInfo = findViewById(R.id.resetOtpEmailInfo);
        emailInfo.setText("Enter the 6-digit OTP generated for " + email);

        verifyButton.setOnClickListener(v -> verifyOtp());
        resendButton.setOnClickListener(v -> resendOtp());

        findViewById(R.id.backToSignIn).setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        // Start initial 60-second cooldown since an OTP was just requested
        startResendCooldown(60);
    }

    private void verifyOtp() {
        String otp = otpInput.getText().toString().trim();

        if (!otp.matches("\\d{6}")) {
            otpStatus.setTextColor(0xFFD32F2F);
            otpStatus.setText("Please enter a valid 6-digit OTP.");
            return;
        }

        verifyButton.setEnabled(false);
        resendButton.setEnabled(false);
        otpStatus.setTextColor(0xFF1769AA);
        otpStatus.setText("Verifying OTP...");

        ApiClient.get(this).verifyResetOtp(new VerifyResetOtpRequest(email, otp))
                .enqueue(new Callback<Map<String, Object>>() {
                    @Override
                    public void onResponse(
                            Call<Map<String, Object>> call,
                            Response<Map<String, Object>> response) {

                        verifyButton.setEnabled(true);
                        resendButton.setEnabled(true);

                        if (response.isSuccessful() && response.body() != null) {
                            otpStatus.setTextColor(0xFF2E7D32);
                            otpStatus.setText("OTP verified successfully!");

                            Object tokenObj = response.body().get("resetToken");
                            String resetToken = tokenObj != null ? tokenObj.toString() : "";

                            Intent intent = new Intent(
                                    ResetOtpVerificationActivity.this,
                                    SetNewPasswordActivity.class
                            );
                            intent.putExtra("email", email);
                            intent.putExtra("resetToken", resetToken);
                            startActivity(intent);
                            finish();
                        } else {
                            otpStatus.setTextColor(0xFFD32F2F);
                            otpStatus.setText(readError(response));
                        }
                    }

                    @Override
                    public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                        verifyButton.setEnabled(true);
                        resendButton.setEnabled(true);
                        otpStatus.setTextColor(0xFFD32F2F);
                        otpStatus.setText("Connection error: " + t.getMessage());
                    }
                });
    }

    private void resendOtp() {
        resendButton.setEnabled(false);
        verifyButton.setEnabled(false);
        otpStatus.setTextColor(0xFF1769AA);
        otpStatus.setText("Requesting a new OTP...");

        ApiClient.get(this).forgotPassword(new ForgotPasswordRequest(email))
                .enqueue(new Callback<Map<String, Object>>() {
                    @Override
                    public void onResponse(
                            Call<Map<String, Object>> call,
                            Response<Map<String, Object>> response) {

                        verifyButton.setEnabled(true);

                        if (response.isSuccessful()) {
                            otpStatus.setTextColor(0xFF2E7D32);
                            otpStatus.setText("A new OTP has been sent to your email.");
                            startResendCooldown(60);
                        } else {
                            resendButton.setEnabled(true);
                            otpStatus.setTextColor(0xFFD32F2F);
                            otpStatus.setText(readError(response));
                        }
                    }

                    @Override
                    public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                        verifyButton.setEnabled(true);
                        resendButton.setEnabled(true);
                        otpStatus.setTextColor(0xFFD32F2F);
                        otpStatus.setText("Connection error: " + t.getMessage());
                    }
                });
    }

    private void startResendCooldown(int seconds) {
        if (resendTimer != null) {
            resendTimer.cancel();
        }

        resendButton.setEnabled(false);

        resendTimer = new CountDownTimer(seconds * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                long sec = millisUntilFinished / 1000;
                resendButton.setText("RESEND OTP (" + sec + "s)");
            }

            @Override
            public void onFinish() {
                resendButton.setEnabled(true);
                resendButton.setText("RESEND OTP");
            }
        }.start();
    }

    private String readError(Response<?> response) {
        try {
            if (response.errorBody() != null) {
                JSONObject error = new JSONObject(response.errorBody().string());
                return error.optString("message", "Request failed (" + response.code() + ")");
            }
        } catch (Exception ignored) {
        }
        return "Request failed (" + response.code() + ")";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (resendTimer != null) {
            resendTimer.cancel();
        }
    }
}
