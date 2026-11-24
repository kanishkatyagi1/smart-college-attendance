# MILESTONE VERIFICATION REPORT
## Smart College Attendance & Academic Management System
### Audit Date: 2026-11-23 | Based on actual source code inspection

---

## MILESTONE 1 — User Authentication

**Status: COMPLETE**

### What is implemented
- Student, Teacher, HOD registration with email OTP verification
- BCrypt password hashing
- JWT generation (HS256, 24h expiry, claims: email/userId/role)
- Stateless JWT filter validates every protected request
- Role-specific routing in Android (`DashboardActivity` branches on `role` from JWT)
- Invalid credentials return HTTP 401
- Logout clears SharedPreferences + FLAG_ACTIVITY_CLEAR_TASK (back-stack safe)
- Password reset: 3-step (forgot → OTP → new password)
- Teacher and HOD accounts require approval before login succeeds

### Relevant Source Files
- `android-app/.../MainActivity.java` — login form, role routing
- `android-app/.../RegisterActivity.java` — registration form
- `android-app/.../OtpVerificationActivity.java` — OTP entry
- `android-app/.../ForgotPasswordActivity.java`, `SetNewPasswordActivity.java`
- `backend/.../controller/AuthController.java`
- `backend/.../service/AuthService.java`
- `backend/.../service/EmailOtpService.java`
- `backend/.../service/PasswordResetService.java`
- `backend/.../security/JwtService.java`
- `backend/.../security/JwtFilter.java`

### API Endpoints
`POST /api/auth/login` `POST /api/auth/register/student` `POST /api/auth/register/teacher`
`POST /api/auth/register/hod` `POST /api/auth/verify-otp` `POST /api/auth/resend-otp`
`POST /api/auth/forgot-password` `POST /api/auth/verify-reset-otp` `POST /api/auth/reset-password`

### Database Entities
`users` (email, BCrypt password, role, emailVerified, approvalStatus, active)
`email_verification_otps` `password_reset_otps`

### Tests Executed
- Backend: `EncryptionUtilTest` passes (automated). `AuthController` endpoints tested via Postman collection.
- No dedicated auth unit test class found in test sources — manual testing.

### Screenshot Evidence Available
Requires device/emulator testing. Login screen XML exists at `activity_main.xml`.

### Remaining Work
- Login screenshot for each role
- Invalid login screenshot (401 error shown in UI)

---

## MILESTONE 2 — Role-Based Access Control

**Status: COMPLETE**

### What is implemented
URL-level RBAC in `SecurityConfig.filterChain()`:
- `/api/student/**` and `/api/face/**` → `hasRole('STUDENT')`
- `/api/teacher/**` → `hasRole('TEACHER')`
- `/api/hod/**` → `hasRole('HOD')`
- `/api/admin/**` → `hasRole('ADMIN')`
- `/api/auth/**` and `/api/health` → `permitAll()`

Method-level `@PreAuthorize` on `FaceRegistrationController` and `AcademicController`.

Custom 403 response body: `{"message":"You do not have permission..."}`.

Teacher owns their session (teacher.getId() check in `TeacherController.close()` and `attendance()`).

### Relevant Source Files
- `backend/.../config/SecurityConfig.java`
- `backend/.../security/JwtFilter.java`
- `backend/.../controller/FaceRegistrationController.java` (`@PreAuthorize`)
- `backend/.../controller/AcademicController.java` (`@PreAuthorize`)
- `backend/.../controller/TeacherController.java` (owner checks)

### API Endpoints
Enforced on all protected endpoints listed in Milestone 1 API inventory.

### Database Entities
`users.role` (STUDENT / TEACHER / HOD / ADMIN)

### Tests Executed
Automated: Spring Security integration via `FaceControllerTest` (401/403 paths). Manual: cross-role access NOT yet screenshot-captured on device.

### Remaining Work
- Screenshot: Student attempting a Teacher endpoint → 403 response
- Screenshot: Unauthenticated request → 401 response

---

## MILESTONE 3 — Teacher Creates Attendance Session

**Status: COMPLETE**

### What is implemented
Full session lifecycle in Android + Spring Boot:

1. Teacher selects subject and enters duration in `DashboardActivity.createSession()`
2. `POST /api/teacher/session/create` validates subject belongs to teacher, section matches
3. Session saved with `startTime=now`, `endTime=now+minutes`, `active=true`
4. Duplicate active session for same class rejected with HTTP 409
5. `POST /api/teacher/session/{id}/close` sets `active=false`, `endTime=now`
6. `GET /api/teacher/sessions` returns session history ordered by startTime
7. Students retrieve active session via `GET /api/student/active-session` (filtered by student's section)

### Relevant Source Files
- `android-app/.../ui/DashboardActivity.java` — `createSession()`, `closeSession()`, `showSessions()`
- `backend/.../controller/TeacherController.java` — `/session/create`, `/session/{id}/close`, `/sessions`
- `backend/.../controller/StudentController.java` — `/active-session`
- `backend/.../entity/AttendanceSession.java`
- `backend/.../repo/AttendanceSessionRepository.java`

### API Endpoints
`POST /api/teacher/session/create` `POST /api/teacher/session/{id}/close`
`GET /api/teacher/sessions` `GET /api/teacher/session/{id}/attendance`
`GET /api/student/active-session`

**Dev-only (NOT in production):** `/api/dev/session/**` — behind `@Profile("dev")`, not accessible in release builds.

### Database Entities
`attendance_sessions` (id, subject_id, teacher_id, section, startTime, endTime, active)
`subjects` (code, name, section, semester, teacher_id)

### Tests Executed
Manual device testing. No dedicated automated test for session creation.

### Screenshot Evidence Available
Teacher create-session UI exists in `DashboardActivity`. Requires running application.

### Remaining Work
- Screenshot: Teacher creates session on device
- Screenshot: Session appears in session list
- Screenshot: Student sees active session

---

## MILESTONE 4 — Student Marks Attendance (Face / Fingerprint)

**Status: FACE — COMPLETE | FINGERPRINT — NOT IMPLEMENTED**

### Face Attendance — What is implemented

**Android:**
- `FaceCaptureActivity` uses CameraX (front camera default, can switch)
- `LocationHelper` retrieves GPS coordinates and detects mock location
- Photo captured as JPEG, sent as multipart to Spring Boot

**Spring Boot:**
- `AttendanceVerificationService.start()` creates single-use attempt (120s TTL, pessimistic write lock)
- Rate limit: 5 attempts per 10 minutes per student
- `GeofenceService.check()`: validates coordinates, mock-location, accuracy, Haversine distance ≤ 90m
- `FaceVerificationService.verifyStudentFace()`: decrypts registered embedding, calls Flask, computes cosine similarity

**Flask:**
- `decode_image_upright()`: EXIF-aware decoding (deterministic, never double-rotates)
- YuNet face detection: exactly one face required
- `check_liveness()`: quality gate (blur/brightness/face-size) + MiniFASNetV2+V1SE ensemble
- SFace 128-dim embedding extraction (only after liveness passes)

**Thresholds:**
- Liveness: 0.65 (measured: genuine 0.7581, spoof max 0.44)
- Similarity: 0.60 (cosine)
- Blur: 50.0 (Laplacian var on 100×100 normalised face region)

### Fingerprint Attendance
**NOT IMPLEMENTED.** No `BiometricPrompt`, `FingerprintManager`, or fingerprint-related API call exists anywhere in the Android app, Spring Boot, or Flask code. The Android OS biometric screen unlock is a device OS feature, not an application-level fingerprint attendance feature. This project does not implement fingerprint attendance.

### Relevant Source Files
- `android-app/.../ui/FaceCaptureActivity.java`
- `android-app/.../util/LocationHelper.java`
- `backend/.../controller/FaceController.java`
- `backend/.../controller/FaceRegistrationController.java`
- `backend/.../service/FaceRegistrationService.java`
- `backend/.../service/FaceVerificationService.java`
- `backend/.../service/AttendanceVerificationService.java`
- `backend/.../service/GeofenceService.java`
- `backend/.../util/EncryptionUtil.java`
- `face-service/app.py` — `/extract-embedding` route
- `face-service/face_utils.py` — YuNet + SFace
- `face-service/anti_spoof.py` — MiniFASNetV2+V1SE ensemble

### API Endpoints
`POST /api/face/register` `POST /api/student/attendance/verification-attempt`
`POST /api/face/verify` (multipart: image + lat + lng + accuracy + mockLocation + attemptId)

### Database Entities
`face_embeddings` `attendance_verification_attempts` `attendance_records`

### Tests Executed
Flask: 106 automated tests (all pass). Backend: FaceControllerTest (12 tests pass). Android compile: BUILD SUCCESSFUL.

### Remaining Work
- End-to-end device screenshot: face registration
- End-to-end device screenshot: face verification success
- Printed photo rejection screenshot
- Screen replay rejection screenshot

---

## MILESTONE 5 — Attendance Stored in Database

**Status: COMPLETE**

### What is implemented
- `AttendanceRecord` entity with `UNIQUE(session_id, student_id)` DB constraint — prevents duplicate attendance at both application and database levels
- Record fields: session, student, markedAt, status ("PRESENT"), verificationMethod ("FACE"), latitude, longitude, accuracyMeters, distanceMeters
- `AttendanceVerificationAttempt` tracks the lifecycle (PENDING/VERIFYING/SUCCEEDED/FAILED/EXPIRED) separately from the attendance record
- Successful verification → one `AttendanceRecord` created via `AttendanceVerificationService.markSuccessful()`
- Failed verification → no `AttendanceRecord` created; attempt marked FAILED
- `FaceEmbedding` stores nonce + ciphertext + tag (AES-256-GCM) with timestamps and encryption version

### Relevant Source Files
- `backend/.../entity/AttendanceRecord.java`
- `backend/.../entity/AttendanceVerificationAttempt.java`
- `backend/.../entity/FaceEmbedding.java`
- `backend/.../service/AttendanceVerificationService.java`
- `backend/.../repo/AttendanceRecordRepository.java`

### API Endpoints
(Internal — triggered by `/api/face/verify` success path)

### Database Entities
`attendance_records` `attendance_verification_attempts` `face_embeddings`

### Tests Executed
Backend: `FaceControllerTest` verifies attendance record creation on success path and no-record on spoof path.

### Remaining Work
- MySQL screenshot showing attendance_records table with a real row
- Confirm UNIQUE constraint enforced with duplicate attempt test

---

## MILESTONE 6 — View Attendance

**Status: COMPLETE (Student + Teacher) / PARTIALLY COMPLETE (HOD)**

### What is implemented

**Student:**
- `GET /api/student/attendance` → all records for student, ordered by markedAt desc
- `GET /api/student/attendance/summary` → per-subject: total sessions, attended sessions, percentage

**Teacher:**
- `GET /api/teacher/session/{id}/attendance` → all attendance records for a specific session
- Teacher owns-session check enforced

**HOD:**
- `GET /api/hod/attendance` → ALL attendance records (no filter)
- `GET /api/hod/analytics` → `{students:N, attendanceRecords:M, note:"..."}` — raw counts only

### Gaps
- HOD cannot filter by date, subject, section, or student
- HOD analytics has no percentage calculation

### Relevant Source Files
- `backend/.../controller/StudentController.java` — `attendance()`, `attendanceSummary()`
- `backend/.../controller/TeacherController.java` — `attendance(Long id)`
- `backend/.../controller/HodController.java` — `attendance()`, `analytics()`
- `android-app/.../ui/DashboardActivity.java` — `studentAttendance()`, `sessionAttendance()`, `analytics()`

### API Endpoints
`GET /api/student/attendance` `GET /api/student/attendance/summary`
`GET /api/teacher/session/{id}/attendance` `GET /api/hod/attendance` `GET /api/hod/analytics`

### Database Entities
`attendance_records` `attendance_sessions` `subjects` `students`

### Remaining Work
- Screenshot: Student attendance history list
- Screenshot: Student attendance summary with percentages
- Implement HOD attendance filter (future work)

---

## MILESTONE 7 — Basic Search

**Status: NOT IMPLEMENTED**

### What exists
- `GET /api/hod/students` returns all students (no query parameter)
- `GET /api/teacher/students` returns all students in teacher's sections (no query parameter)
- `GET /api/student/active-session` filters by student's section — this is a system lookup, not a user search
- No Android UI search box or filter exists in `DashboardActivity`

### What is missing
- No `?search=`, `?name=`, `?rollNumber=`, or any filter parameter on any endpoint
- No Android `SearchView` or text filter UI
- No backend search/filter logic

### Recommendation
Add a `?search=` query parameter to `GET /api/hod/students` and `GET /api/teacher/students` using Spring JPA `@Query` with LIKE. Add an `EditText` filter to the Android list views. This is a small change.

---

## MILESTONE 8 — Basic Dashboard

**Status: COMPLETE (Student + Teacher) / PARTIALLY COMPLETE (HOD)**

### Student Dashboard — COMPLETE
Real data from live API:
- Profile: name, roll number, course, semester, section
- Active session indicator
- Attendance history list with markedAt timestamps
- Per-subject attendance percentage summary
- Assignment list

### Teacher Dashboard — COMPLETE
Real data from live API:
- Profile: name, employeeId, department
- Subject list
- Session history list
- Session attendance detail
- Student list for teacher's sections
- Assignment creation

### HOD Dashboard — PARTIALLY COMPLETE
Real data:
- Student count
- Teacher count
- All attendance records
- Pending teacher approvals
- Complaint list

Partial / not actionable:
- Analytics shows raw counts only — no percentage, no chart, no breakdown
- No subject management from dashboard
- No attendance filter or report download

---

*End of MILESTONE_VERIFICATION_REPORT.md*
