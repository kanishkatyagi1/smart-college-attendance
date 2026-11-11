
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
 private String role;
 private String savedName;
 private boolean isHome = true;

 @Override
 public void onCreate(Bundle state) {
  super.onCreate(state);
  setContentView(R.layout.activity_dashboard);

  title = findViewById(R.id.title);
  welcome = findViewById(R.id.welcome);
  out = findViewById(R.id.output);
  actions = findViewById(R.id.actions);
  progress = findViewById(R.id.progress);

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

  toolbarBack.setVisibility(View.GONE);

  title.setText(role + " MODULE");
  welcome.setText("Welcome, " + savedName
          + "\nAccess is based on your role.");

  actions.removeAllViews();

  out.setText("");
  out.setVisibility(View.GONE);

  progress.setVisibility(View.GONE);

  build();
 }

 private void showInnerScreen() {
  isHome = false;
  toolbarBack.setVisibility(View.VISIBLE);

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
 // ROLE-BASED DASHBOARD
 // =========================

 private void btn(String text, View.OnClickListener listener) {
  Button b = new Button(this);
  b.setText(text);
  b.setAllCaps(false);
  b.setTextSize(16);
  b.setMinHeight(dp(52));
  b.setOnClickListener(listener);
  actions.addView(b);
 }

 private void build() {

  if (role.equals("STUDENT")) {

   btn("1. Mark Attendance (Face)", v -> loadActive());
   btn("Register / Update Face", v -> registerFace());
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

   ApiClient.get(this).studentProfile()
           .enqueue(new Callback<ProfileResponse>() {

            @Override
            public void onResponse(
                    Call<ProfileResponse> call,
                    Response<ProfileResponse> response) {

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
             error("Network error: " + t.getMessage());
            }
           });

  } else if (role.equals("TEACHER")) {

   loading("Loading teacher profile...");

   ApiClient.get(this).teacherProfile()
           .enqueue(new Callback<ProfileResponse>() {

            @Override
            public void onResponse(
                    Call<ProfileResponse> call,
                    Response<ProfileResponse> response) {

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
             error("Network error: " + t.getMessage());
            }
           });

  } else {

   // HOD profile uses information saved during login.
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
  header.setBackgroundColor(0xFFFFFFFF);

  LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(-1, -2);
  lp.setMargins(0, 0, 0, dp(12));

  actions.addView(header, lp);

  TextView avatar = new TextView(this);
  avatar.setText("👤");
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
  if (p.user == null) return "—";
  return safe(p.user.phone);
 }

 // =========================
 // LOGOUT
 // =========================

 private void confirmLogout() {

  new AlertDialog.Builder(this)
          .setTitle("Logout")
          .setMessage("Are you sure you want to logout?")
          .setNegativeButton("Cancel", null)
          .setPositiveButton("Logout",
                  (dialog, which) -> logout())
          .show();
 }

 private void logout() {

  // Clear authentication only.
  // Keep cfg preferences containing the backend URL.
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
 }

 private void done() {
  progress.setVisibility(View.GONE);
  actions.setEnabled(true);
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
  c.setBackgroundColor(0xFFFFFFFF);

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

  ApiClient.get(this).activeSession()
          .enqueue(new Callback<Session>() {

           public void onResponse(
                   Call<Session> c,
                   Response<Session> r) {

            if (r.isSuccessful() && r.body() != null) {

             Map<String, Object> b = new HashMap<>();
             b.put("sessionId", r.body().id);

             ApiClient.get(DashboardActivity.this)
                     .startVerification(b)
                     .enqueue(new Callback<VerificationAttempt>() {

                      public void onResponse(
                              Call<VerificationAttempt> c,
                              Response<VerificationAttempt> a) {

                       if (a.isSuccessful() && a.body() != null) {

                        Intent i = new Intent(
                                DashboardActivity.this,
                                FaceCaptureActivity.class);

                        i.putExtra("attemptId",
                                a.body().attemptId);

                        startActivity(i);

                       } else {
                        toast("Unable to start verification.");
                       }
                      }

                      public void onFailure(
                              Call<VerificationAttempt> c,
                              Throwable t) {
                       toast(t.getMessage());
                      }
                     });

            } else {
             toast("No active session for your section.");
            }
           }

           public void onFailure(Call<Session> c, Throwable t) {
            toast(t.getMessage());
           }
          });
 }

 private void registerFace() {
  Intent i = new Intent(this, FaceCaptureActivity.class);
  i.putExtra("register", true);
  startActivity(i);
 }

 private void studentAttendance() {

  showInnerScreen();
  loading("Loading attendance...");

  ApiClient.get(this).studentAttendance()
          .enqueue(new Callback<List<AttendanceRecord>>() {

           public void onResponse(
                   Call<List<AttendanceRecord>> c,
                   Response<List<AttendanceRecord>> r) {

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
                     safe(a.status) + "  •  "
                             + format(a.markedAt)
             );
            }
           }

           public void onFailure(
                   Call<List<AttendanceRecord>> c,
                   Throwable t) {
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

  ApiClient.get(this).subjects()
          .enqueue(new Callback<List<Subject>>() {

           public void onResponse(
                   Call<List<Subject>> c,
                   Response<List<Subject>> r) {

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
                     + " — " + r.body().get(i).name
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

  ApiClient.get(this).createSession(b)
          .enqueue(new Callback<Session>() {

           public void onResponse(
                   Call<Session> c,
                   Response<Session> r) {

            done();

            if (r.isSuccessful() && r.body() != null) {
             teacherSessions();
            } else {
             error(callError(r));
            }
           }

           public void onFailure(Call<Session> c, Throwable t) {
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

  ApiClient.get(this).teacherSessions()
          .enqueue(new Callback<List<Session>>() {

           public void onResponse(
                   Call<List<Session>> c,
                   Response<List<Session>> r) {

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
                     : safe(s.subject.code) + " — "
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
            error("Network error: " + t.getMessage());
           }
          });
 }

 private void closeSession(Session s) {

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

                            if (r.isSuccessful()) {
                             teacherSessions();
                            } else {
                             toast(callError(r));
                            }
                           }

                           public void onFailure(Call<Session> c, Throwable t) {
                            toast(t.getMessage());
                           }
                          })
          ).show();
 }

 private void sessionAttendance(Session s) {

  showInnerScreen();
  loading("Loading session attendance...");

  ApiClient.get(this).sessionAttendance(s.id)
          .enqueue(new Callback<List<AttendanceRecord>>() {

           public void onResponse(
                   Call<List<AttendanceRecord>> c,
                   Response<List<AttendanceRecord>> r) {

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
                     (roll.isEmpty() ? "" : roll + " • ")
                             + safe(a.status)
                             + " • " + format(a.markedAt));
            }
           }

           public void onFailure(
                   Call<List<AttendanceRecord>> c,
                   Throwable t) {
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

  ApiClient.get(this).assignments()
          .enqueue(new Callback<List<Assignment>>() {

           public void onResponse(
                   Call<List<Assignment>> c,
                   Response<List<Assignment>> r) {

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
                     : safe(a.subject.code) + " — "
                     + safe(a.subject.name);

             String section = a.subject == null
                     ? "" : a.subject.section;

             card(safe(a.title),
                     subject
                             + (section == null ? "" : " • " + section)
                             + "\nDue " + safe(a.dueDate)
                             + (a.description == null
                             || a.description.isEmpty()
                             ? "" : "\n" + a.description));
            }
           }

           public void onFailure(Call<List<Assignment>> c, Throwable t) {
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

 private void hodStudents() {

  showInnerScreen();
  loading("Loading students...");

  ApiClient.get(this).hodStudents()
          .enqueue(new Callback<List<Student>>() {

           public void onResponse(
                   Call<List<Student>> c,
                   Response<List<Student>> r) {

            done();

            if (!r.isSuccessful()) {
             error(callError(r));
             return;
            }

            out.setText(new com.google.gson.Gson().toJson(r.body()));
           }

           public void onFailure(Call<List<Student>> c, Throwable t) {
            error(t.getMessage());
           }
          });
 }

 private void analytics() {

  showInnerScreen();
  loading("Loading analytics...");

  ApiClient.get(this).analytics()
          .enqueue(new Callback<Map<String, Object>>() {

           public void onResponse(
                   Call<Map<String, Object>> c,
                   Response<Map<String, Object>> r) {

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
  return s == null || s.isBlank() ? "—" : s;
 }

 private String format(String value) {

  if (value == null) return "—";

  try {
   return LocalDateTime.parse(value)
           .format(DateTimeFormatter.ofPattern(
                   "dd MMM yyyy, hh:mm a"));
  } catch (Exception ignored) {
   return value;
  }
 }
}
