package com.college.attendance.mobile.api;

import com.college.attendance.mobile.model.*;
import java.util.*;

import okhttp3.MultipartBody;
import retrofit2.Call;
import retrofit2.http.*;

public interface ApiService {

    // Authentication
    @POST("api/auth/login")
    Call<LoginResponse> login(@Body LoginRequest r);

    @POST("api/auth/register/student")
    Call<Map<String, Object>> registerStudent(
            @Body StudentRegistrationRequest request
    );

    @POST("api/auth/verify-otp")
    Call<Map<String, Object>> verifyOtp(
            @Body Map<String, String> body
    );

    @POST("api/auth/resend-otp")
    Call<Map<String, Object>> resendOtp(
            @Body Map<String, String> body
    );

    // Password Recovery
    @POST("api/auth/forgot-password")
    Call<Map<String, Object>> forgotPassword(
            @Body ForgotPasswordRequest request
    );

    @POST("api/auth/verify-reset-otp")
    Call<Map<String, Object>> verifyResetOtp(
            @Body VerifyResetOtpRequest request
    );

    @POST("api/auth/reset-password")
    Call<Map<String, Object>> resetPassword(
            @Body ResetPasswordRequest request
    );

    // Student
    @GET("api/student/profile")
    Call<ProfileResponse> studentProfile();

    @GET("api/student/attendance")
    Call<List<AttendanceRecord>> studentAttendance();

    @GET("api/student/attendance/summary")
    Call<List<SubjectAttendanceSummary>> studentAttendanceSummary();

    @GET("api/student/active-session")
    Call<Session> activeSession();

    @POST("api/student/attendance/verification-attempt")
    Call<VerificationAttempt> startVerification(
            @Body Map<String, Object> body
    );

    // Teacher
    @GET("api/teacher/profile")
    Call<ProfileResponse> teacherProfile();

    @GET("api/teacher/subjects")
    Call<List<Subject>> subjects();

    @POST("api/teacher/session/create")
    Call<Session> createSession(@Body Map<String,Object> body);

    @POST("api/teacher/session/{id}/close")
    Call<Session> closeSession(@Path("id") Long id);

    @GET("api/teacher/sessions")
    Call<List<Session>> teacherSessions();

    @GET("api/teacher/session/{id}/attendance")
    Call<List<AttendanceRecord>> sessionAttendance(@Path("id") Long id);

    // Academic
    @GET("api/academic/assignments")
    Call<List<Assignment>> assignments();

    // HOD
    @GET("api/hod/students")
    Call<List<Student>> hodStudents();

    @GET("api/hod/teachers")
    Call<List<Object>> hodTeachers();

    @GET("api/hod/attendance")
    Call<List<AttendanceRecord>> hodAttendance();

    @GET("api/hod/analytics")
    Call<Map<String,Object>> analytics();

    // Admin
    @GET("api/admin/students/pending")
    Call<List<AdminStudentResponse>> pendingStudents();

    @POST("api/admin/students/{userId}/approve")
    Call<Map<String, Object>> approveStudent(@Path("userId") Long userId);

    // Face registration
    @Multipart
    @POST("api/face/register")
    Call<FaceResponse> registerFace(
            @Part MultipartBody.Part image
    );

    // Face verification
    @Multipart
    @POST("api/face/verify")
    Call<FaceResponse> verifyFace(
            @Query("attemptId") String attemptId,
            @Query("latitude") Double latitude,
            @Query("longitude") Double longitude,
            @Query("accuracy") Double accuracy,
            @Query("mockLocation") Boolean mockLocation,
            @Part MultipartBody.Part image
    );

    // -----------------------------------------------------------------------
    // Active liveness  (Spring-owned session protocol)
    // Android communicates only with Spring. Flask URL never appears here.
    // -----------------------------------------------------------------------

    /**
     * Start an active-liveness session.
     * purpose == "VERIFICATION" requires a non-null attemptId.
     * purpose == "REGISTRATION" requires attemptId to be null.
     */
    @POST("api/face/active-liveness/sessions")
    Call<ActiveLivenessSession> startLivenessSession(
            @Body ActiveLivenessStartRequest request
    );

    /**
     * Submit one JPEG frame for the given session.
     * {@code sequence} must equal server's {@code nextExpectedSequence}.
     * Max frame size: 1 MiB. Min interval between calls: 100 ms.
     *
     * <p>The part name ("frame") is embedded in the {@link MultipartBody.Part}
     * via {@code createFormData("frame", "frame.jpg", requestBody)}.
     * Retrofit forbids {@code @Part("name")} when the type is already a
     * {@link MultipartBody.Part} — the annotation must have no name.
     */
    @Multipart
    @POST("api/face/active-liveness/sessions/{sessionId}/frames")
    Call<ActiveLivenessSession> submitLivenessFrame(
            @Path("sessionId") String sessionId,
            @Query("sequence") long sequence,
            @Part MultipartBody.Part frame
    );

    /**
     * Poll the current state of an active-liveness session.
     * Used after a network timeout to determine whether the previous frame
     * was accepted before retrying or aborting.
     */
    @GET("api/face/active-liveness/sessions/{sessionId}")
    Call<ActiveLivenessSession> getLivenessSession(
            @Path("sessionId") String sessionId
    );

    /**
     * Cancel an active-liveness session.
     * Must be called when the user navigates away or the activity is destroyed
     * while a session is ACTIVE, to release the server-side slot.
     */
    @DELETE("api/face/active-liveness/sessions/{sessionId}")
    Call<ActiveLivenessSession> cancelLivenessSession(
            @Path("sessionId") String sessionId
    );
}


