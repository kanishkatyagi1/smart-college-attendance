package com.college.attendance.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.college.attendance.mobile.api.ApiClient;
import com.college.attendance.mobile.model.StudentRegistrationRequest;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class RegisterActivity extends AppCompatActivity {

    private EditText name, email, phone, rollNumber, course;
    private EditText semester, section, password, confirmPassword;
    private TextView status;
    private Button registerButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        name = findViewById(R.id.regName);
        email = findViewById(R.id.regEmail);
        phone = findViewById(R.id.regPhone);
        rollNumber = findViewById(R.id.regRollNumber);
        course = findViewById(R.id.regCourse);
        semester = findViewById(R.id.regSemester);
        section = findViewById(R.id.regSection);
        password = findViewById(R.id.regPassword);
        confirmPassword = findViewById(R.id.regConfirmPassword);

        status = findViewById(R.id.registerStatus);
        registerButton = findViewById(R.id.registerStudent);

        registerButton.setOnClickListener(v -> registerStudent());

        findViewById(R.id.backToLogin).setOnClickListener(v -> finish());
    }

    private void registerStudent() {

        String studentName = name.getText().toString().trim();
        String studentEmail = email.getText().toString().trim();
        String studentPhone = phone.getText().toString().trim();
        String studentRoll = rollNumber.getText().toString().trim();
        String studentCourse = course.getText().toString().trim();
        String semesterText = semester.getText().toString().trim();
        String studentSection = section.getText().toString().trim();
        String studentPassword = password.getText().toString();
        String confirm = confirmPassword.getText().toString();

        if (studentName.isEmpty() || studentEmail.isEmpty()
                || studentPhone.isEmpty() || studentRoll.isEmpty()
                || studentCourse.isEmpty() || semesterText.isEmpty()
                || studentSection.isEmpty() || studentPassword.isEmpty()
                || confirm.isEmpty()) {
            status.setText("Please fill all fields.");
            return;
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(studentEmail).matches()) {
            status.setText("Please enter a valid email address.");
            return;
        }
        // GBU domain enforcement – only accept @gbu.ac.in addresses
        if (!studentEmail.trim().toLowerCase(Locale.ROOT).endsWith("@gbu.ac.in")) {
            status.setText("Only GBU institutional emails (ending @gbu.ac.in) are allowed.");
            return;
        }

        int semesterNumber;

        try {
            semesterNumber = Integer.parseInt(semesterText);
        } catch (NumberFormatException e) {
            status.setText("Enter a valid semester.");
            return;
        }

        if (semesterNumber < 1 || semesterNumber > 12) {
            status.setText("Semester must be between 1 and 12.");
            return;
        }

        if (studentPassword.length() < 8) {
            status.setText("Password must contain at least 8 characters.");
            return;
        }

        if (!studentPassword.equals(confirm)) {
            status.setText("Passwords do not match.");
            return;
        }

        StudentRegistrationRequest request = new StudentRegistrationRequest(
                studentName,
                studentEmail,
                studentPhone,
                studentRoll,
                studentCourse,
                semesterNumber,
                studentSection,
                studentPassword,
                confirm
        );

        registerButton.setEnabled(false);
        status.setText("Creating your account...");

        ApiClient.get(this).registerStudent(request)
                .enqueue(new Callback<Map<String, Object>>() {

                    @Override
                    public void onResponse(
                            Call<Map<String, Object>> call,
                            Response<Map<String, Object>> response) {

                        registerButton.setEnabled(true);

                        if (response.isSuccessful() && response.body() != null) {
                            Object message = response.body().get("message");

                            status.setText(message != null
                                    ? message.toString()
                                    : "Registration successful. Please verify your email.");

                            Intent intent = new Intent(
                                    RegisterActivity.this,
                                    OtpVerificationActivity.class
                            );
                            intent.putExtra("email", studentEmail);
                            startActivity(intent);
                            finish();
                        } else {
                            String errorMessage = "Registration failed (" + response.code() + ")";

                            try {
                                if (response.errorBody() != null) {
                                    JSONObject error = new JSONObject(
                                            response.errorBody().string()
                                    );
                                    errorMessage = error.optString("message", errorMessage);
                                }
                            } catch (Exception ignored) {
                            }

                            status.setText(errorMessage);
                        }
                    }

                    @Override
                    public void onFailure(
                            Call<Map<String, Object>> call,
                            Throwable t) {

                        registerButton.setEnabled(true);
                        status.setText("Backend connection error: " + t.getMessage());
                    }
                });
    }
}
