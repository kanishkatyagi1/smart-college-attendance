
package com.college.attendance.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.college.attendance.mobile.R;
import com.college.attendance.mobile.api.ApiClient;
import com.college.attendance.mobile.model.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import retrofit2.*;

public class DashboardActivity extends AppCompatActivity {

    private LinearLayout actions;
    private TextView title, welcome, out;
    private TextView toolbarBack, profileButton, logoutButton;
    private ProgressBar progress;
    private LinearLayout studentOverview, attendanceSummary;
    private TextView studentInfo;

    private String role;
    private String savedName;
    private boolean isHome = true;
    /** Prevents multiple logout dialogs from stacking on rapid taps. */
    private AlertDialog logoutDialog;
    /** Generation token -- incremented on every navigation. Stale async
     *  callbacks compare their captured token against this and bail out. */
    private int navToken = 0;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_dashboard);

        title = findViewById(R.id.title);
        welcome = findViewById(R.id.welcome);
        out = findViewById(R.id.output);
        actions = findViewById(R.id.actions);
        progress = findViewById(R.id.progress);

        studentOverview = findViewById(R.id.studentOverview);
        studentInfo = findViewById(R.id.studentInfo);
        attendanceSummary = findViewById(R.id.attendanceSummary);

        toolbarBack = findViewById(R.id.toolbarBack);
        profileButton = findViewById(R.id.profileButton);
        logoutButton = findViewById(R.id.logoutButton);

        role = getSharedPreferences("auth", 0)
                .getString("role", "STUDENT");

        savedName = getSharedPreferences("auth", 0)
                .getString("name", "");

        toolbarBack.setOnClickListener(v -> showHome());
        profileButton.setOnClickListener(v -> showProfile());
        logoutButton.setOnClickListener(v -> confirmLogout());

        showHome();
    }

    // =========================
    // DASHBOARD NAVIGATION
    // =========================

    private void showHome() {
        isHome = true;
        navToken++;

        toolbarBack.setVisibility(View.GONE);
        toolbarBack.setEnabled(true);
        profileButton.setEnabled(true);
        logoutButton.setEnabled(true);

        title.setText(role + " MODULE");
        welcome.setText("Welcome, " + savedName
                + "\nAccess is based on your role.");

        actions.removeAllViews();

        out.setText("");
        out.setVisibility(View.GONE);

        progress.setVisibility(View.GONE);

        build();

        if ("STUDENT".equals(role)) {
            studentOverview.setVisibility(View.VISIBLE);
            loadStudentOverview();
        } else {
            studentOverview.setVisibility(View.GONE);
        }
    }

    private void showInnerScreen() {
        isHome = false;
        navToken++;
        toolbarBack.setVisibility(View.VISIBLE);

        studentOverview.setVisibility(View.GONE);

        actions.removeAllViews();

        out.setText("");
        out.setVisibility(View.GONE);

        progress.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (!isHome) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    // =========================
    // PREMIUM ACTION CARDS
    // =========================

    private void btn(String text, View.OnClickListener listener) {

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();

        bg.setColor(android.graphics.Color.WHITE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0xFFE5ECF5);

        card.setBackground(bg);
        card.setElevation(dp(3));
        card.setClickable(true);
        card.setFocusable(true);

        // Icon
        TextView icon = new TextView(this);
        icon.setTextSize(23);
        icon.setGravity(android.view.Gravity.CENTER);
        icon.setTextColor(0xFF155EEF);

        String lower = text.toLowerCase(Locale.ROOT);

        if (lower.contains("attendance")) {
            icon.setText("\u2713");
        } else if (lower.contains("face")) {
            icon.setText("\u25C9");
        } else if (lower.contains("assignment")) {
            icon.setText("\u25A4");
        } else if (lower.contains("feedback")
                || lower.contains("complaint")
                || lower.contains("doubt")) {
            icon.setText("\u2709");
        } else if (lower.contains("session")) {
            icon.setText("\u25B6");
        } else if (lower.contains("student")) {
            icon.setText("\u265F");
        } else if (lower.contains("analytics")
                || lower.contains("monitoring")) {
            icon.setText("\u25A5");
        } else if (lower.contains("management")) {
            icon.setText("\u2699");
        } else {
            icon.setText("\u25A0");
        }

        android.graphics.drawable.GradientDrawable iconBg =
                new android.graphics.drawable.GradientDrawable();

        iconBg.setColor(0xFFEAF2FF);
        iconBg.setCornerRadius(dp(14));
        icon.setBackground(iconBg);

        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dp(52), dp(52));

        iconParams.setMarginEnd(dp(14));
        card.addView(icon, iconParams);

        // Action title
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(15);
        label.setTextColor(0xFF102B50);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(0, -2, 1);

        card.addView(label, labelParams);

        // Arrow
        TextView arrow = new TextView(this);
        arrow.setText("\u203A");
        arrow.setTextSize(30);
        arrow.setTextColor(0xFF155EEF);
        arrow.setGravity(android.view.Gravity.CENTER);

        card.addView(arrow,
                new LinearLayout.LayoutParams(dp(28), dp(45)));

        card.setOnClickListener(listener);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);

        params.bottomMargin = dp(14);

        actions.addView(card, params);
    }

    private void build() {

        if (role.equals("STUDENT")) {

            btn("1. Mark Attendance (Face)", v -> loadActive());
            btn("Register Face", v -> registerFace());
            btn("2. View Attendance", v -> studentAttendance());
            btn("3. Assignments", v -> assignments());
            btn("4. Feedback / Complaints", v -> complaintDialog());

        } else if (role.equals("TEACHER")) {

            btn("1. Create Attendance Session", v -> createSession());
            btn("2. View Sessions", v -> teacherSessions());
            btn("3. View Student Attendance",
                    v -> teacherSessionsForAttendance());
            btn("4. Assignments", v -> assignments());
            btn("5. Doubts / Complaints", v -> teacherComplaints());

        } else if (role.equals("ADMIN")) {

            btn("1. Pending Student Approvals", v -> pendingStudents());

        } else {

            btn("1. Search & View Students", v -> hodStudents());
            btn("2. Monitoring / Analytics", v -> analytics());
            btn("3. Complaints & Feedback",
                    v -> toast("HOD complaints screen is not connected yet."));
            btn("4. User Management",
                    v -> toast("User management screen is not connected yet."));
        }
    }

    // =========================
    // PROFILE
    // =========================

    private void showProfile() {
        showInnerScreen();

        title.setText("MY PROFILE");
        welcome.setText("Your account information");

        if (role.equals("STUDENT")) {

            loading("Loading student profile...");

            final int token = navToken;

            ApiClient.get(this).studentProfile()
                    .enqueue(new Callback<ProfileResponse>() {

                        @Override
                        public void onResponse(
                                Call<ProfileResponse> call,
                                Response<ProfileResponse> response) {

                            if (token != navToken) return;

                            done();

                            if (!response.isSuccessful()
                                    || response.body() == null) {
                                error(callError(response));
                                return;
                            }

                            ProfileResponse p = response.body();

                            addProfileHeader(
                                    p.user != null ? p.user.name : savedName,
                                    p.user != null ? p.user.email : ""
                            );

                            profileCard("Account Details",
                                    "Role: " + role
                                            + "\nPhone: " + userPhone(p));

                            profileCard("Academic Details",
                                    "Roll Number: " + safe(p.rollNumber)
                                            + "\nCourse: " + safe(p.course)
                                            + "\nSemester: " + p.semester
                                            + "\nSection: " + safe(p.section));
                        }

                        @Override
                        public void onFailure(
                                Call<ProfileResponse> call,
                                Throwable t) {
                            if (token != navToken) return;
                            error("Network error: " + t.getMessage());
                        }
                    });

        } else if (role.equals("TEACHER")) {

            loading("Loading teacher profile...");

            final int token = navToken;

            ApiClient.get(this).teacherProfile()
                    .enqueue(new Callback<ProfileResponse>() {

                        @Override
                        public void onResponse(
                                Call<ProfileResponse> call,
                                Response<ProfileResponse> response) {

                            if (token != navToken) return;

                            done();

                            if (!response.isSuccessful()
                                    || response.body() == null) {
                                error(callError(response));
                                return;
                            }

                            ProfileResponse p = response.body();

                            addProfileHeader(
                                    p.user != null ? p.user.name : savedName,
                                    p.user != null ? p.user.email : ""
                            );

                            profileCard("Account Details",
                                    "Role: " + role
                                            + "\nPhone: " + userPhone(p));

                            profileCard("Professional Details",
                                    "Employee ID: " + safe(p.employeeId)
                                            + "\nDepartment: " + safe(p.department));
                        }

                        @Override
                        public void onFailure(
                                Call<ProfileResponse> call,
                                Throwable t) {
                            if (token != navToken) return;
                            error("Network error: " + t.getMessage());
                        }
                    });

        } else {

            String email = getSharedPreferences("auth", 0)
                    .getString("email", "");

            String phone = getSharedPreferences("auth", 0)
                    .getString("phone", "");

            done();

            addProfileHeader(savedName, email);

            profileCard("Account Details",
                    "Role: HOD\nPhone: " + safe(phone));
        }
    }

    private void addProfileHeader(String name, String email) {

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(android.view.Gravity.CENTER);
        header.setPadding(dp(20), dp(24), dp(20), dp(24));

        android.graphics.drawable.GradientDrawable headerBg =
                new android.graphics.drawable.GradientDrawable();

        headerBg.setColor(0xFFFFFFFF);
        headerBg.setCornerRadius(dp(20));
        header.setBackground(headerBg);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, -2);

        lp.setMargins(0, 0, 0, dp(12));
        actions.addView(header, lp);

        TextView avatar = new TextView(this);
        avatar.setText("USER");
        avatar.setTextSize(42);
        header.addView(avatar);

        TextView nameView = new TextView(this);
        nameView.setText(safe(name));
        nameView.setTextSize(21);
        nameView.setTextColor(0xFF07579B);
        nameView.setTypeface(null, 1);
        nameView.setPadding(0, dp(8), 0, dp(4));
        header.addView(nameView);

        TextView emailView = new TextView(this);
        emailView.setText(safe(email));
        emailView.setTextSize(14);
        emailView.setTextColor(0xFF64748B);
        header.addView(emailView);
    }

    private void profileCard(String heading, String details) {
        card(heading, details);
    }

    private String userPhone(ProfileResponse p) {
        if (p.user == null) return " - ";
        return safe(p.user.phone);
    }

    // =========================
    // LOGOUT
    // =========================

    private void confirmLogout() {

        if (logoutDialog != null && logoutDialog.isShowing()) return;

        logoutButton.setEnabled(false);

        logoutDialog = new AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Logout",
                        (dialog, which) -> logout())
                .setOnDismissListener(
                        d -> {
                            logoutButton.setEnabled(true);
                            logoutDialog = null;
                        })
                .show();
    }

    private void logout() {

        getSharedPreferences("auth", 0)
                .edit()
                .clear()
                .apply();

        Intent intent = new Intent(this,
                com.college.attendance.mobile.MainActivity.class);

        intent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
        );

        startActivity(intent);
        finish();
    }

    // =========================
    // COMMON UI HELPERS
    // =========================

    private int dp(int n) {
        return (int) (n * getResources()
                .getDisplayMetrics().density + .5f);
    }

    private void loading(String text) {
        progress.setVisibility(View.VISIBLE);
        out.setVisibility(View.VISIBLE);
        out.setText(text);
        actions.setEnabled(false);
        profileButton.setEnabled(false);
        logoutButton.setEnabled(false);
    }

    private void done() {
        progress.setVisibility(View.GONE);
        actions.setEnabled(true);
        profileButton.setEnabled(true);
        logoutButton.setEnabled(true);
    }

    private void empty(String text) {
        out.setVisibility(View.VISIBLE);
        out.setText(text);
    }

    private void error(String text) {
        done();
        out.setVisibility(View.VISIBLE);
        out.setText(text);
    }

    private String callError(Response<?> r) {
        return r.code() == 401
                ? "Your session expired. Please log in again."
                : r.code() == 403
                ? "You do not have permission to view this information."
                : "Could not load data (HTTP " + r.code() + ").";
    }

    private void card(String heading, String detail) {

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();

        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0xFFE5ECF5);

        c.setBackground(bg);
        c.setElevation(dp(2));

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, -2);

        lp.setMargins(0, 0, 0, dp(10));
        actions.addView(c, lp);

        TextView h = new TextView(this);
        h.setText(heading);
        h.setTextSize(17);
        h.setTextColor(0xFF07579B);
        h.setTypeface(null, 1);
        c.addView(h);

        TextView d = new TextView(this);
        d.setText(detail);
        d.setTextSize(14);
        d.setTextColor(0xFF334155);
        d.setPadding(0, dp(6), 0, 0);
        c.addView(d);
    }

    // =========================
    // STUDENT ATTENDANCE
    // =========================

    private void loadActive() {

        final int token = navToken;

        ApiClient.get(this).activeSession()
                .enqueue(new Callback<Session>() {

                    public void onResponse(
                            Call<Session> c,
                            Response<Session> r) {

                        if (token != navToken) return;

                        if (r.isSuccessful() && r.body() != null) {

                            Map<String, Object> b = new HashMap<>();
                            b.put("sessionId", r.body().id);

                            ApiClient.get(DashboardActivity.this)
                                    .startVerification(b)
                                    .enqueue(new Callback<VerificationAttempt>() {

                                        public void onResponse(
                                                Call<VerificationAttempt> c,
                                                Response<VerificationAttempt> a) {

                                            if (token != navToken) return;

                                            if (a.isSuccessful() && a.body() != null) {

                                                Intent i = new Intent(
                                                        DashboardActivity.this,
                                                        ActiveLivenessActivity.class);

                                                i.putExtra(ActiveLivenessActivity.EXTRA_ATTEMPT_ID,
                                                        a.body().attemptId);

                                                i.putExtra(ActiveLivenessActivity.EXTRA_SESSION_ID,
                                                        a.body().sessionId);

                                                i.putExtra(ActiveLivenessActivity.EXTRA_REGISTER,
                                                        false);

                                                startActivity(i);

                                            } else {
                                                toast("Unable to start verification.");
                                            }
                                        }

                                        public void onFailure(
                                                Call<VerificationAttempt> c,
                                                Throwable t) {
                                            if (token != navToken) return;
                                            toast(t.getMessage());
                                        }
                                    });

                        } else {
                            toast("No active session for your section.");
                        }
                    }

                    public void onFailure(Call<Session> c, Throwable t) {
                        if (token != navToken) return;
                        toast(t.getMessage());
                    }
                });
    }

    private void registerFace() {
        Intent i = new Intent(this, ActiveLivenessActivity.class);
        i.putExtra(ActiveLivenessActivity.EXTRA_REGISTER, true);
        startActivity(i);
    }

    // =========================
    // STUDENT DASHBOARD OVERVIEW
    // =========================

    private void loadStudentOverview() {

        final int token = navToken;

        studentInfo.setText("Loading academic profile...");
        attendanceSummary.removeAllViews();

        ApiClient.get(this).studentProfile()
                .enqueue(new Callback<ProfileResponse>() {
                    @Override
                    public void onResponse(
                            Call<ProfileResponse> call,
                            Response<ProfileResponse> response) {

                        if (token != navToken) return;

                        if (!response.isSuccessful() || response.body() == null) {
                            studentInfo.setText("Unable to load academic profile.");
                            return;
                        }

                        ProfileResponse p = response.body();

                        studentInfo.setText(
                                "Course: " + dashboardText(p.course)
                                + "\nRoll Number: " + dashboardText(p.rollNumber)
                                + "\nSemester: " + p.semester
                                + "    |    Section: " + dashboardText(p.section)
                        );
                    }

                    @Override
                    public void onFailure(
                            Call<ProfileResponse> call,
                            Throwable t) {
                        if (token != navToken) return;
                        studentInfo.setText("Profile unavailable. Please try again.");
                    }
                });

        attendanceSummary.addView(dashboardTextView(
                "Loading subject attendance...", 14, 0xFF64748B, false
        ));

        ApiClient.get(this).studentAttendanceSummary()
                .enqueue(new Callback<List<SubjectAttendanceSummary>>() {
                    @Override
                    public void onResponse(
                            Call<List<SubjectAttendanceSummary>> call,
                            Response<List<SubjectAttendanceSummary>> response) {

                        if (token != navToken) return;

                        attendanceSummary.removeAllViews();

                        if (!response.isSuccessful()) {
                            attendanceSummary.addView(dashboardTextView(
                                    "Unable to load attendance summary.",
                                    14, 0xFFDC2626, false
                            ));
                            return;
                        }

                        List<SubjectAttendanceSummary> rows = response.body();

                        if (rows == null || rows.isEmpty()) {
                            attendanceSummary.addView(dashboardTextView(
                                    "No subjects or completed classes found yet.",
                                    14, 0xFF64748B, false
                            ));
                            return;
                        }

                        int totalClasses = 0;
                        int attendedClasses = 0;

                        for (SubjectAttendanceSummary item : rows) {
                            totalClasses += item.totalClasses;
                            attendedClasses += item.attendedClasses;
                        }

                        double overall = totalClasses == 0 ? 0
                                : attendedClasses * 100.0 / totalClasses;

                        addDashboardAttendanceCard(
                                "OVERALL ATTENDANCE",
                                attendedClasses + " / " + totalClasses + " classes",
                                overall,
                                true
                        );

                        for (SubjectAttendanceSummary item : rows) {
                            String code = dashboardText(item.subjectCode);
                            String name = dashboardText(item.subjectName);

                            addDashboardAttendanceCard(
                                    code + "  |  " + name,
                                    item.attendedClasses + " / "
                                            + item.totalClasses + " classes attended",
                                    item.attendancePercentage,
                                    false
                            );
                        }
                    }

                    @Override
                    public void onFailure(
                            Call<List<SubjectAttendanceSummary>> call,
                            Throwable t) {

                        if (token != navToken) return;

                        attendanceSummary.removeAllViews();
                        attendanceSummary.addView(dashboardTextView(
                                "Network error while loading attendance.",
                                14, 0xFFDC2626, false
                        ));
                    }
                });
    }

    private void addDashboardAttendanceCard(
            String heading,
            String detail,
            double percentage,
            boolean overall) {

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));

        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(overall ? 0xFFEAF2FF : 0xFFFFFFFF);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), 0xFFE2E8F0);

        card.setBackground(background);
        card.setElevation(dp(2));

        TextView headingView = dashboardTextView(
                heading, overall ? 14 : 15, 0xFF102B50, true
        );
        card.addView(headingView);

        TextView detailView = dashboardTextView(
                detail, 13, 0xFF64748B, false
        );

        LinearLayout.LayoutParams detailParams =
                new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(7);
        card.addView(detailView, detailParams);

        int safePercentage = (int) Math.round(
                Math.max(0, Math.min(100, percentage))
        );

        ProgressBar bar = new ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal
        );
        bar.setMax(100);
        bar.setProgress(safePercentage);

        LinearLayout.LayoutParams barParams =
                new LinearLayout.LayoutParams(-1, dp(8));
        barParams.topMargin = dp(13);
        card.addView(bar, barParams);

        TextView percentView = dashboardTextView(
                String.format(Locale.US, "%.1f%%", percentage),
                17, 0xFF155EEF, true
        );
        percentView.setGravity(android.view.Gravity.END);

        LinearLayout.LayoutParams percentParams =
                new LinearLayout.LayoutParams(-1, -2);
        percentParams.topMargin = dp(7);
        card.addView(percentView, percentParams);

        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(12);

        attendanceSummary.addView(card, cardParams);
    }

    private TextView dashboardTextView(
            String value, int size, int color, boolean bold) {

        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);

        if (bold) {
            view.setTypeface(null, android.graphics.Typeface.BOLD);
        }

        return view;
    }

    private String dashboardText(String value) {
        return value == null || value.trim().isEmpty()
                ? "Not available" : value;
    }
    private void studentAttendance() {

        showInnerScreen();
        loading("Loading attendance...");

        final int token = navToken;

        ApiClient.get(this).studentAttendance()
                .enqueue(new Callback<List<AttendanceRecord>>() {

                    public void onResponse(
                            Call<List<AttendanceRecord>> c,
                            Response<List<AttendanceRecord>> r) {

                        if (token != navToken) return;

                        done();

                        if (!r.isSuccessful()) {
                            error(callError(r));
                            return;
                        }

                        List<AttendanceRecord> rows = r.body();

                        if (rows == null || rows.isEmpty()) {
                            empty("No attendance records yet.");
                            return;
                        }

                        for (AttendanceRecord a : rows) {
                            card(
                                    a.session != null && a.session.subject != null
                                            ? a.session.subject.name : "Attendance",
                                    safe(a.status) + " - "
                                            + format(a.markedAt)
                            );
                        }
                    }

                    public void onFailure(
                            Call<List<AttendanceRecord>> c,
                            Throwable t) {
                        if (token != navToken) return;
                        error("Network error: " + t.getMessage());
                    }
                });
    }

    // =========================
    // TEACHER SESSIONS
    // =========================

    private void createSession() {

        showInnerScreen();
        loading("Loading assigned subjects...");

        final int token = navToken;

        ApiClient.get(this).subjects()
                .enqueue(new Callback<List<Subject>>() {

                    public void onResponse(
                            Call<List<Subject>> c,
                            Response<List<Subject>> r) {

                        if (token != navToken) return;

                        done();

                        if (!r.isSuccessful()) {
                            error(callError(r));
                            return;
                        }

                        if (r.body() == null || r.body().isEmpty()) {
                            empty("No subjects are assigned to your account.");
                            return;
                        }

                        String[] labels = new String[r.body().size()];

                        for (int i = 0; i < labels.length; i++) {
                            labels[i] = r.body().get(i).code
                                    + " - " + r.body().get(i).name
                                    + " (" + r.body().get(i).section + ")";
                        }

                        new AlertDialog.Builder(DashboardActivity.this)
                                .setTitle("Select subject")
                                .setItems(labels,
                                        (d, which) ->
                                                startSession(r.body().get(which)))
                                .setNegativeButton("Cancel", null)
                                .show();
                    }

                    public void onFailure(Call<List<Subject>> c, Throwable t) {
                        if (token != navToken) return;
                        error("Network error: " + t.getMessage());
                    }
                });
    }

    private void startSession(Subject s) {

        Map<String, Object> b = new HashMap<>();
        b.put("subjectId", s.id);
        b.put("section", s.section);
        b.put("durationMinutes", 15);

        loading("Creating attendance session...");

        final int token = navToken;

        ApiClient.get(this).createSession(b)
                .enqueue(new Callback<Session>() {

                    public void onResponse(
                            Call<Session> c,
                            Response<Session> r) {

                        if (token != navToken) return;

                        done();

                        if (r.isSuccessful() && r.body() != null) {
                            teacherSessions();
                        } else {

                            String details = "HTTP Status: " + r.code()
                                    + "\nMessage: " + r.message();

                            try {
                                if (r.errorBody() != null) {
                                    details += "\nServer Response: "
                                            + r.errorBody().string();
                                }
                            } catch (Exception e) {
                                details += "\nCould not read server response.";
                            }

                            error(details);
                        }
                    }

                    public void onFailure(Call<Session> c, Throwable t) {
                        if (token != navToken) return;
                        error("Network error: " + t.getMessage());
                    }
                });
    }

    private void teacherSessions() {
        showSessions(false);
    }

    private void teacherSessionsForAttendance() {
        showSessions(true);
    }

    private void showSessions(boolean attendanceMode) {

        showInnerScreen();
        loading("Loading sessions...");

        final int token = navToken;

        ApiClient.get(this).teacherSessions()
                .enqueue(new Callback<List<Session>>() {

                    public void onResponse(
                            Call<List<Session>> c,
                            Response<List<Session>> r) {

                        if (token != navToken) return;

                        done();

                        if (!r.isSuccessful()) {
                            error(callError(r));
                            return;
                        }

                        List<Session> rows = r.body();

                        if (rows == null || rows.isEmpty()) {
                            empty("No attendance sessions yet.");
                            return;
                        }

                        for (Session s : rows) {

                            String subject = s.subject == null
                                    ? "Subject"
                                    : safe(s.subject.code) + " - "
                                    + safe(s.subject.name);

                            card(subject,
                                    "Section " + safe(s.section)
                                            + "\n" + format(s.startTime)
                                            + " to " + format(s.endTime)
                                            + "\n" + (s.active ? "ACTIVE" : "CLOSED"));

                            Button view = new Button(DashboardActivity.this);
                            view.setText("View Attendance");
                            view.setAllCaps(false);

                            actions.addView(view);
                            view.setOnClickListener(v -> sessionAttendance(s));

                            if (s.active) {
                                Button close = new Button(DashboardActivity.this);
                                close.setText("Close Session");
                                close.setAllCaps(false);

                                actions.addView(close);
                                close.setOnClickListener(v -> closeSession(s));
                            }
                        }
                    }

                    public void onFailure(Call<List<Session>> c, Throwable t) {
                        if (token != navToken) return;
                        error("Network error: " + t.getMessage());
                    }
                });
    }

    private void closeSession(Session s) {

        final int token = navToken;

        new AlertDialog.Builder(this)
                .setTitle("Close session?")
                .setMessage("Students will no longer be able to mark attendance in this session.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Close", (d, w) ->

                        ApiClient.get(this).closeSession(s.id)
                                .enqueue(new Callback<Session>() {

                                    public void onResponse(
                                            Call<Session> c,
                                            Response<Session> r) {

                                        if (token != navToken) return;

                                        if (r.isSuccessful()) {
                                            teacherSessions();
                                        } else {
                                            toast(callError(r));
                                        }
                                    }

                                    public void onFailure(Call<Session> c, Throwable t) {
                                        if (token != navToken) return;
                                        toast(t.getMessage());
                                    }
                                })
                ).show();
    }

    private void sessionAttendance(Session s) {

        showInnerScreen();
        loading("Loading session attendance...");

        final int token = navToken;

        ApiClient.get(this).sessionAttendance(s.id)
                .enqueue(new Callback<List<AttendanceRecord>>() {

                    public void onResponse(
                            Call<List<AttendanceRecord>> c,
                            Response<List<AttendanceRecord>> r) {

                        if (token != navToken) return;

                        done();

                        if (!r.isSuccessful()) {
                            error(callError(r));
                            return;
                        }

                        List<AttendanceRecord> rows = r.body();

                        if (rows == null || rows.isEmpty()) {
                            empty("No attendance has been recorded for this session.");
                            return;
                        }

                        int present = 0;

                        for (AttendanceRecord a : rows) {
                            if ("PRESENT".equalsIgnoreCase(a.status)) {
                                present++;
                            }
                        }

                        card("Attendance summary",
                                present + " present out of " + rows.size());

                        for (AttendanceRecord a : rows) {

                            Student st = a.student;
                            String roll = st == null
                                    ? "" : safe(st.rollNumber);

                            card("Student",
                                    (roll.isEmpty() ? "" : roll + " - ")
                                            + safe(a.status)
                                            + " - " + format(a.markedAt));
                        }
                    }

                    public void onFailure(
                            Call<List<AttendanceRecord>> c,
                            Throwable t) {
                        if (token != navToken) return;
                        error("Network error: " + t.getMessage());
                    }
                });
    }

    // =========================
    // ASSIGNMENTS
    // =========================

    private void assignments() {

        showInnerScreen();
        loading("Loading assignments...");

        final int token = navToken;

        ApiClient.get(this).assignments()
                .enqueue(new Callback<List<Assignment>>() {

                    public void onResponse(
                            Call<List<Assignment>> c,
                            Response<List<Assignment>> r) {

                        if (token != navToken) return;

                        done();

                        if (!r.isSuccessful()) {
                            error(callError(r));
                            return;
                        }

                        if (r.body() == null || r.body().isEmpty()) {
                            empty("No assignments found.");
                            return;
                        }

                        for (Assignment a : r.body()) {

                            String subject = a.subject == null
                                    ? "Subject"
                                    : safe(a.subject.code) + " - "
                                    + safe(a.subject.name);

                            String section = a.subject == null
                                    ? "" : a.subject.section;

                            card(safe(a.title),
                                    subject
                                            + (section == null ? "" : " - " + section)
                                            + "\nDue " + safe(a.dueDate)
                                            + (a.description == null
                                            || a.description.isEmpty()
                                            ? "" : "\n" + a.description));
                        }
                    }

                    public void onFailure(Call<List<Assignment>> c, Throwable t) {
                        if (token != navToken) return;
                        error("Network error: " + t.getMessage());
                    }
                });
    }

    // =========================
    // COMPLAINTS
    // =========================

    private void teacherComplaints() {
        showInnerScreen();
        empty("Teacher complaint review is not available: "
                + "the backend has no endpoint to list student complaints. "
                + "Only HOD complaint administration is supported.");
    }

    private void complaintDialog() {

        EditText e = new EditText(this);
        e.setHint("Complaint / feedback");

        new AlertDialog.Builder(this)
                .setTitle("Feedback / Complaint")
                .setView(e)
                .setPositiveButton("Submit",
                        (d, w) ->
                                toast("Complaint submission is not connected yet."))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // =========================
    // HOD
    // =========================

    // =========================
    // ADMIN - PENDING STUDENT APPROVALS
    // =========================

    private void pendingStudents() {

        showInnerScreen();
        loading("Loading pending student registrations...");

        final int token = navToken;

        ApiClient.get(this).pendingStudents()
                .enqueue(new Callback<List<AdminStudentResponse>>() {

                    @Override
                    public void onResponse(
                            Call<List<AdminStudentResponse>> c,
                            Response<List<AdminStudentResponse>> r) {

                        if (token != navToken) return;

                        done();

                        if (!r.isSuccessful()) {
                            error(callError(r));
                            return;
                        }

                        List<AdminStudentResponse> students = r.body();

                        if (students == null || students.isEmpty()) {
                            empty("No pending student registrations.");
                            return;
                        }

                        for (AdminStudentResponse student : students) {

                            String details =
                                    "Email: " + student.getEmail()
                                    + "\nPhone: " + student.getPhone()
                                    + "\nRoll Number: " + student.getRollNumber()
                                    + "\nCourse: " + student.getCourse()
                                    + "\nSemester: " + student.getSemester()
                                    + "\nSection: " + student.getSection();

                            String studentName = student.getName() == null
                                    ? "Student"
                                    : student.getName();

                            card(studentName, details);

                            View lastCard = actions.getChildAt(
                                    actions.getChildCount() - 1);

                            if (lastCard instanceof LinearLayout) {

                                Button approveButton = new Button(DashboardActivity.this);
                                approveButton.setText("Approve Student");
                                approveButton.setOnClickListener(
                                        v -> confirmStudentApproval(student));

                                ((LinearLayout) lastCard).addView(approveButton);
                            }
                        }
                    }

                    @Override
                    public void onFailure(
                            Call<List<AdminStudentResponse>> c,
                            Throwable t) {

                        if (token != navToken) return;
                        error(t.getMessage());
                    }
                });
    }

    private void confirmStudentApproval(AdminStudentResponse student) {

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Approve Student")
                .setMessage("Approve registration for "
                        + student.getName() + "?")
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("Approve", (dialog, which) ->
                        approvePendingStudent(student))
                .show();
    }

    private void approvePendingStudent(AdminStudentResponse student) {

        if (student == null || student.getUserId() == null) {
            error("Invalid student information.");
            return;
        }

        final int token = navToken;

        ApiClient.get(this).approveStudent(student.getUserId())
                .enqueue(new Callback<Map<String, Object>>() {

                    @Override
                    public void onResponse(
                            Call<Map<String, Object>> c,
                            Response<Map<String, Object>> r) {

                        if (token != navToken) return;

                        if (!r.isSuccessful()) {
                            error(callError(r));
                            return;
                        }

                        toast("Student approved successfully!");
                        pendingStudents();
                    }

                    @Override
                    public void onFailure(
                            Call<Map<String, Object>> c,
                            Throwable t) {

                        if (token != navToken) return;
                        error(t.getMessage());
                    }
                });
    }
    private void hodStudents() {

        showInnerScreen();
        loading("Loading students...");

        final int token = navToken;

        ApiClient.get(this).hodStudents()
                .enqueue(new Callback<List<Student>>() {

                    public void onResponse(
                            Call<List<Student>> c,
                            Response<List<Student>> r) {

                        if (token != navToken) return;

                        done();

                        if (!r.isSuccessful()) {
                            error(callError(r));
                            return;
                        }

                        out.setVisibility(View.VISIBLE);
                        out.setText(new com.google.gson.Gson().toJson(r.body()));
                    }

                    public void onFailure(Call<List<Student>> c, Throwable t) {
                        if (token != navToken) return;
                        error(t.getMessage());
                    }
                });
    }

    private void analytics() {

        showInnerScreen();
        loading("Loading analytics...");

        final int token = navToken;

        ApiClient.get(this).analytics()
                .enqueue(new Callback<Map<String, Object>>() {

                    public void onResponse(
                            Call<Map<String, Object>> c,
                            Response<Map<String, Object>> r) {

                        if (token != navToken) return;

                        done();

                        if (r.isSuccessful() && r.body() != null) {

                            for (Map.Entry<String, Object> e
                                    : r.body().entrySet()) {
                                card(e.getKey(), String.valueOf(e.getValue()));
                            }

                        } else {
                            error(callError(r));
                        }
                    }

                    public void onFailure(
                            Call<Map<String, Object>> c,
                            Throwable t) {
                        if (token != navToken) return;
                        error(t.getMessage());
                    }
                });
    }

    // =========================
    // GENERAL HELPERS
    // =========================

    private void toast(String s) {
        Toast.makeText(this,
                s == null ? "Error" : s,
                Toast.LENGTH_LONG).show();
    }

    private String safe(String s) {
        return s == null || s.isBlank() ? " - " : s;
    }

    private String format(String value) {

        if (value == null) return " - ";

        try {
            return LocalDateTime.parse(value)
                    .format(DateTimeFormatter.ofPattern(
                            "dd MMM yyyy, hh:mm a"));
        } catch (Exception ignored) {
            return value;
        }
    }
}


