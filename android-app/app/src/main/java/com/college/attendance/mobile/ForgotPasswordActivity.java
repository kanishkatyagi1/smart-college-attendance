package com.college.attendance.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.college.attendance.mobile.api.ApiClient;
import com.college.attendance.mobile.model.ForgotPasswordRequest;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ForgotPasswordActivity extends AppCompatActivity {

    private EditText emailInput;
    private Button sendOtpButton;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        emailInput = findViewById(R.id.resetEmailInput);
        sendOtpButton = findViewById(R.id.sendOtpButton);
        status = findViewById(R.id.resetStatus);

        sendOtpButton.setOnClickListener(v -> sendResetOtp());

        findViewById(R.id.backToLogin).setOnClickListener(v -> finish());
    }

    private void sendResetOtp() {
        String email = emailInput.getText().toString().trim();

        if (email.isEmpty()) {
            status.setTextColor(0xFFD32F2F);
            status.setText("Please enter your registered email address.");
            return;
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            status.setTextColor(0xFFD32F2F);
            status.setText("Please enter a valid email address.");
            return;
        }

        // Strict GBU institutional domain validation
        String lowerEmail = email.toLowerCase(Locale.ROOT);
        if (!lowerEmail.matches("^[a-z0-9._%+-]+@gbu\\.ac\\.in$")) {
            status.setTextColor(0xFFD32F2F);
            status.setText("Only official GBU institutional emails (@gbu.ac.in) are allowed.");
            return;
        }

        // Prevent repeated requests while processing
        sendOtpButton.setEnabled(false);
        status.setTextColor(0xFF1769AA);
        status.setText("Sending reset OTP...");

        ApiClient.get(this).forgotPassword(new ForgotPasswordRequest(lowerEmail))
                .enqueue(new Callback<Map<String, Object>>() {
                    @Override
                    public void onResponse(
                            Call<Map<String, Object>> call,
                            Response<Map<String, Object>> response) {

                        sendOtpButton.setEnabled(true);

                        if (response.isSuccessful()) {
                            status.setTextColor(0xFF2E7D32);
                            status.setText("OTP sent! Navigating to verification...");

                            Intent intent = new Intent(
                                    ForgotPasswordActivity.this,
                                    ResetOtpVerificationActivity.class
                            );
                            intent.putExtra("email", lowerEmail);
                            startActivity(intent);
                        } else {
                            status.setTextColor(0xFFD32F2F);
                            status.setText(readError(response));
                        }
                    }

                    @Override
                    public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                        sendOtpButton.setEnabled(true);
                        status.setTextColor(0xFFD32F2F);
                        status.setText("Connection error: " + t.getMessage());
                    }
                });
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
}
