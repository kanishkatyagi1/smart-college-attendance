package com.college.attendance.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.college.attendance.mobile.api.ApiClient;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class OtpVerificationActivity extends AppCompatActivity {

    private EditText otpInput;
    private TextView otpStatus;
    private Button verifyButton, resendButton;
    private String email;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_otp_verification);

        otpInput = findViewById(R.id.otpInput);
        otpStatus = findViewById(R.id.otpStatus);
        verifyButton = findViewById(R.id.verifyOtpButton);
        resendButton = findViewById(R.id.resendOtpButton);

        email = getIntent().getStringExtra("email");

        if (email == null || email.trim().isEmpty()) {
            otpStatus.setText("Email address is missing.");
            verifyButton.setEnabled(false);
            resendButton.setEnabled(false);
            return;
        }

        TextView emailInfo = findViewById(R.id.otpEmailInfo);
        emailInfo.setText("Enter the 6-digit OTP generated for " + email);

        verifyButton.setOnClickListener(v -> verifyOtp());
        resendButton.setOnClickListener(v -> resendOtp());
    }

    private void verifyOtp() {

        String otp = otpInput.getText().toString().trim();

        if (!otp.matches("\\d{6}")) {
            otpStatus.setText("Please enter a valid 6-digit OTP.");
            return;
        }

        verifyButton.setEnabled(false);
        resendButton.setEnabled(false);
        otpStatus.setText("Verifying email...");

        Map<String, String> body = new HashMap<>();
        body.put("email", email);
        body.put("otp", otp);

        ApiClient.get(this).verifyOtp(body)
                .enqueue(new Callback<Map<String, Object>>() {

                    @Override
                    public void onResponse(
                            Call<Map<String, Object>> call,
                            Response<Map<String, Object>> response) {

                        verifyButton.setEnabled(true);
                        resendButton.setEnabled(true);

                        if (response.isSuccessful() && response.body() != null) {

                            otpStatus.setText("Email verified successfully!");

                            Intent intent = new Intent(
                                    OtpVerificationActivity.this,
                                    MainActivity.class
                            );

                            intent.addFlags(
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            );

                            startActivity(intent);
                            finish();

                        } else {
                            otpStatus.setText(readError(response));
                        }
                    }

                    @Override
                    public void onFailure(
                            Call<Map<String, Object>> call,
                            Throwable t) {

                        verifyButton.setEnabled(true);
                        resendButton.setEnabled(true);
                        otpStatus.setText(
                                "Connection error: " + t.getMessage()
                        );
                    }
                });
    }

    private void resendOtp() {

        resendButton.setEnabled(false);
        verifyButton.setEnabled(false);
        otpStatus.setText("Requesting a new OTP...");

        Map<String, String> body = new HashMap<>();
        body.put("email", email);

        ApiClient.get(this).resendOtp(body)
                .enqueue(new Callback<Map<String, Object>>() {

                    @Override
                    public void onResponse(
                            Call<Map<String, Object>> call,
                            Response<Map<String, Object>> response) {

                        verifyButton.setEnabled(true);
                        resendButton.setEnabled(true);

                        if (response.isSuccessful() && response.body() != null) {
                            Object message = response.body().get("message");
                            otpStatus.setText(message != null
                                    ? message.toString()
                                    : "OTP request successful.");
                        } else {
                            otpStatus.setText(readError(response));
                        }
                    }

                    @Override
                    public void onFailure(
                            Call<Map<String, Object>> call,
                            Throwable t) {

                        verifyButton.setEnabled(true);
                        resendButton.setEnabled(true);
                        otpStatus.setText(
                                "Connection error: " + t.getMessage()
                        );
                    }
                });
    }

    private String readError(Response<?> response) {
        try {
            if (response.errorBody() != null) {
                JSONObject error = new JSONObject(
                        response.errorBody().string()
                );
                return error.optString(
                        "message",
                        "Request failed (" + response.code() + ")"
                );
            }
        } catch (Exception ignored) {
        }

        return "Request failed (" + response.code() + ")";
    }
}
