package com.college.attendance.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.college.attendance.mobile.api.ApiClient;
import com.college.attendance.mobile.model.ResetPasswordRequest;

import org.json.JSONObject;

import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SetNewPasswordActivity extends AppCompatActivity {

    private EditText newPasswordInput, confirmPasswordInput;
    private TextView toggleNewPassword, toggleConfirmPassword;
    private Button resetPasswordButton;
    private TextView status;

    private String email;
    private String resetToken;

    private boolean isNewPasswordVisible = false;
    private boolean isConfirmPasswordVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_set_new_password);

        newPasswordInput = findViewById(R.id.newPasswordInput);
        confirmPasswordInput = findViewById(R.id.confirmNewPasswordInput);
        toggleNewPassword = findViewById(R.id.toggleNewPassword);
        toggleConfirmPassword = findViewById(R.id.toggleConfirmPassword);
        resetPasswordButton = findViewById(R.id.resetPasswordButton);
        status = findViewById(R.id.newPasswordStatus);

        email = getIntent().getStringExtra("email");
        resetToken = getIntent().getStringExtra("resetToken");

        if (email == null || email.trim().isEmpty() || resetToken == null || resetToken.trim().isEmpty()) {
            status.setTextColor(0xFFD32F2F);
            status.setText("Password reset session is invalid. Please start over.");
            resetPasswordButton.setEnabled(false);
            return;
        }

        toggleNewPassword.setOnClickListener(v -> {
            isNewPasswordVisible = !isNewPasswordVisible;
            if (isNewPasswordVisible) {
                newPasswordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                toggleNewPassword.setText("HIDE");
            } else {
                newPasswordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                toggleNewPassword.setText("SHOW");
            }
            newPasswordInput.setSelection(newPasswordInput.getText().length());
        });

        toggleConfirmPassword.setOnClickListener(v -> {
            isConfirmPasswordVisible = !isConfirmPasswordVisible;
            if (isConfirmPasswordVisible) {
                confirmPasswordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                toggleConfirmPassword.setText("HIDE");
            } else {
                confirmPasswordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                toggleConfirmPassword.setText("SHOW");
            }
            confirmPasswordInput.setSelection(confirmPasswordInput.getText().length());
        });

        resetPasswordButton.setOnClickListener(v -> submitNewPassword());

        findViewById(R.id.backToSignIn).setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
    }

    private void submitNewPassword() {
        String newPassword = newPasswordInput.getText().toString();
        String confirmPassword = confirmPasswordInput.getText().toString();

        if (newPassword.isEmpty() || confirmPassword.isEmpty()) {
            status.setTextColor(0xFFD32F2F);
            status.setText("Please fill all password fields.");
            return;
        }

        if (newPassword.length() < 8) {
            status.setTextColor(0xFFD32F2F);
            status.setText("Password must contain at least 8 characters.");
            return;
        }

        if (!newPassword.equals(confirmPassword)) {
            status.setTextColor(0xFFD32F2F);
            status.setText("New password and confirm password do not match.");
            return;
        }

        resetPasswordButton.setEnabled(false);
        status.setTextColor(0xFF1769AA);
        status.setText("Resetting password...");

        ApiClient.get(this).resetPassword(new ResetPasswordRequest(email, resetToken, newPassword, confirmPassword))
                .enqueue(new Callback<Map<String, Object>>() {
                    @Override
                    public void onResponse(
                            Call<Map<String, Object>> call,
                            Response<Map<String, Object>> response) {

                        resetPasswordButton.setEnabled(true);

                        if (response.isSuccessful()) {
                            Intent intent = new Intent(SetNewPasswordActivity.this, MainActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            intent.putExtra(
                                    "success_message",
                                    "Password reset successfully. Please sign in with your new password."
                            );
                            startActivity(intent);
                            finish();
                        } else {
                            status.setTextColor(0xFFD32F2F);
                            status.setText(readError(response));
                        }
                    }

                    @Override
                    public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                        resetPasswordButton.setEnabled(true);
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
