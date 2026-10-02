package com.college.attendance.mobile.api;
import com.college.attendance.mobile.model.*; import java.util.*; import okhttp3.MultipartBody; import okhttp3.RequestBody; import retrofit2.Call; import retrofit2.http.*;
public interface ApiService {
 @POST("api/auth/login") Call<LoginResponse> login(@Body LoginRequest r);
 @GET("api/student/profile") Call<Student> profile(); @GET("api/student/attendance") Call<List<AttendanceRecord>> studentAttendance(); @GET("api/student/active-session") Call<Session> activeSession(); @POST("api/student/attendance/mark") Call<AttendanceRecord> mark(@Body Map<String,Object> body);
 @GET("api/teacher/subjects") Call<List<Subject>> subjects(); @POST("api/teacher/session/create") Call<Session> createSession(@Body Map<String,Object> body); @POST("api/teacher/session/{id}/close") Call<Session> closeSession(@Path("id") Long id); @GET("api/teacher/sessions") Call<List<Session>> teacherSessions(); @GET("api/teacher/session/{id}/attendance") Call<List<AttendanceRecord>> sessionAttendance(@Path("id") Long id);
 @GET("api/hod/students") Call<List<Student>> hodStudents(); @GET("api/hod/teachers") Call<List<Object>> hodTeachers(); @GET("api/hod/attendance") Call<List<AttendanceRecord>> hodAttendance(); @GET("api/hod/analytics") Call<Map<String,Object>> analytics();
 @Multipart @POST("api/face/register/{studentId}") Call<FaceResponse> registerFace(@Path("studentId") Long id,@Part MultipartBody.Part image);
 @Multipart @POST("api/face/verify") Call<FaceResponse> verifyFace(@Query("studentId") Long studentId,@Query("sessionId") Long sessionId,@Part MultipartBody.Part image);
}
