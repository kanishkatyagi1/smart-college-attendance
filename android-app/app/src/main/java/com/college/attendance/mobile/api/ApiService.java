
package com.college.attendance.mobile.api;

import com.college.attendance.mobile.model.*;
import java.util.*;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.*;

public interface ApiService {

 // Authentication
 @POST("api/auth/login")
 Call<LoginResponse> login(@Body LoginRequest r);

 // Student
 @GET("api/student/profile")
 Call<ProfileResponse> studentProfile();

 @GET("api/student/attendance")
 Call<List<AttendanceRecord>> studentAttendance();

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

 // Face verification
 @Multipart
 @POST("api/face/register")
 Call<FaceResponse> registerFace(@Part MultipartBody.Part image);

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
}
