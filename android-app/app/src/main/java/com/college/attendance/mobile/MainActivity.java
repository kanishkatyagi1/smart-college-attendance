package com.college.attendance.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.college.attendance.mobile.api.ApiClient;
import com.college.attendance.mobile.model.LoginRequest;
import com.college.attendance.mobile.model.LoginResponse;
import com.college.attendance.mobile.ui.DashboardActivity;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    EditText email, password;
    TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        email = findViewById(R.id.email);
        password = findViewById(R.id.password);
        status = findViewById(R.id.status);

        findViewById(R.id.login).setOnClickListener(v -> login());

        findViewById(R.id.openRegister).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, RegisterActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.forgotPassword).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ForgotPasswordActivity.class);
            startActivity(intent);
        });

        displaySuccessMessageIfPresent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        displaySuccessMessageIfPresent(intent);
    }

    private void displaySuccessMessageIfPresent(Intent intent) {
        if (intent != null && intent.hasExtra("success_message")) {
            String msg = intent.getStringExtra("success_message");
            if (msg != null && !msg.isEmpty()) {
                status.setTextColor(0xFF2E7D32); // Green
                status.setText(msg);
            }
        }
    }

    private void login() {

        String userEmail = email.getText().toString().trim();
        String userPassword = password.getText().toString();

        status.setTextColor(0xFFD32F2F);
        if (userEmail.isEmpty() || userPassword.isEmpty()) {
            status.setText("Please enter email and password");
            return;
        }

        status.setText("Logging in...");

        ApiClient.get(this)
                .login(new LoginRequest(userEmail, userPassword))
                .enqueue(new Callback<LoginResponse>() {

                    @Override
                    public void onResponse(
                            Call<LoginResponse> call,
                            Response<LoginResponse> response) {

                        if (response.isSuccessful()
                                && response.body() != null) {

                            LoginResponse result = response.body();

                            getSharedPreferences("auth", MODE_PRIVATE)
                                    .edit()
                                    .putString("token", result.token)
                                    .putString("role", result.role)
                                    .putString("name", result.name)
                                    .apply();

                            Intent intent = new Intent(
                                    MainActivity.this,
                                    DashboardActivity.class
                            );

                            startActivity(intent);
                            finish();

                        } else {

                            String error = "";

                            try {
                                if (response.errorBody() != null) {
                                    error = response.errorBody().string();
                                }
                            } catch (Exception e) {
                                error = e.getMessage();
                            }

                            status.setText(
                                    "Login failed (" +
                                    response.code() + "): " + error
                            );
                        }
                    }

                    @Override
                    public void onFailure(
                            Call<LoginResponse> call,
                            Throwable t) {

                        status.setText(
                                "Backend error: " + t.getMessage()
                        );
                    }
                });
    }
}
