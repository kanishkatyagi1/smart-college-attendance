# Smart College Attendance & Academic Management System
## PROJECT CURRENT STATE AUDIT
### Generated: 2026-11-23 | Read-only audit of actual source code

---

## EXECUTIVE SUMMARY

The Smart College Attendance & Academic Management System is a **production-grade** three-tier application:

- **Android** (Java/CameraX/Retrofit) — one shared `DashboardActivity` for all three roles, with `FaceCaptureActivity` for biometric capture.
- **Spring Boot 3.5.5** (Java 17/Spring Security/JPA) — 10 REST controllers, 10 services, 16 entities.
- **Python Flask** face service — YuNet detection + SFace embedding + MiniFASNetV2+V1SE anti-spoofing ensemble.
- **MySQL 8** — schema auto-managed by JPA/Hibernate.

**What genuinely works end-to-end:** Student registration (email+OTP), login (JWT), face registration (AES-256-GCM encrypted embedding), attendance verification (face detection → anti-spoofing → embedding comparison → attendance record), teacher session creation/close, HOD student/teacher management and pending-teacher approval.

**What is partially implemented:** Attendance summary (API complete, Android UI confirmed in `DashboardActivity`). Assignments and complaints (backend complete, Android shows them). HOD analytics returns a simple count aggregate, not a percentage breakdown.

**What is not implemented:** Fingerprint attendance (no code exists anywhere). Active liveness (challenge/response). HOD department-level search, reports, or pagination.

**Security posture is strong:** JWT stateless auth, BCrypt password hashing, AES-256-GCM face embedding encryption, geofencing, mock-location detection, single-use attempts with pessimistic locking, rate-limiting (5 attempts/10 min), EXIF-deterministic image decoding, service-to-service shared secret with `hmac.compare_digest`.

---

## 1. PROJECT OVERVIEW

| Item | Value |
|---|---|
| Project name | Smart College Attendance & Academic Management System |
| Backend port | 8080 |
| Face service port | 5001 |
| Database | `smart_attendance` (MySQL 8) |
| Android min SDK | Declared in `build.gradle` |
| JWT expiry | 86,400,000 ms (24 hours) |
| Verification attempt TTL | 120 seconds (configurable) |
| Rate limit | 5 attempts / 10 minutes per student |
| Geofence centre | 28.417341°N, 77.523483°E (ICT Building) |
| Geofence radius | 90 m |
| GPS accuracy gate | 50 m max |
| Similarity threshold | 0.60 (cosine, configurable via `FACE_VERIFICATION_THRESHOLD`) |
| Liveness threshold | 0.65 (configurable via `FACE_LIVENESS_THRESHOLD`) |
| Blur threshold | 50.0 (normalised 100×100 Laplacian variance) |

---

## 2. TECHNOLOGY STACK

### Android
- Java (minSdk from `build.gradle`)
- CameraX (`ProcessCameraProvider`, `ImageCapture`)
- Retrofit 2 + OkHttp multipart
- `LocationHelper` (GPS + mock-location detection via `LocationManager`)

### Backend
- Spring Boot 3.5.5, Java 17
- Spring Security (stateless JWT filter)
- Spring Data JPA + Hibernate (`ddl-auto=update`)
- `jjwt` for JWT generation/validation
- `spring-boot-starter-mail` for OTP emails
- `RestTemplate` for Flask calls
- BCryptPasswordEncoder

### Face Service
- Python 3.11, Flask 3.1.2
- opencv-python 4.12.0.88 (YuNet + SFace bundled)
- onnxruntime 1.20.1 (MiniFASNetV2 + MiniFASNetV1SE)
- numpy 2.2.6
- stdlib `struct`, `hmac` (no extra dependencies for diagnostics or security)

### Database
- MySQL 8, schema managed by JPA/Hibernate
- Tables created automatically on first run

---

## 3. SYSTEM ARCHITECTURE

```
┌──────────────────────────────────────────────────────┐
│  Android App (Java / CameraX / Retrofit)             │
│  Activities: MainActivity, DashboardActivity,        │
│              FaceCaptureActivity, RegisterActivity,  │
│              OtpVerificationActivity,                │
│              ForgotPasswordActivity, SetNewPassword  │
└────────────────────┬─────────────────────────────────┘
                     │  HTTPS:8080  JWT Bearer
                     ▼
┌──────────────────────────────────────────────────────┐
│  Spring Boot 3.5.5 (port 8080)                       │
│  SecurityConfig → JwtFilter → Controllers            │
│  AuthController, StudentController, TeacherController│
│  FaceController, FaceRegistrationController,         │
│  HodController, AcademicController, AdminController, │
│  HealthController, DevAttendanceSessionController    │
│  Services: AuthService, FaceRegistrationService,     │
│            FaceVerificationService, GeofenceService, │
│            AttendanceVerificationService, ...        │
│  EncryptionUtil (AES-256-GCM)                        │
└──────┬─────────────────────────────┬─────────────────┘
       │  HTTP:5001 (internal only)  │  JDBC
       │  X-FACE-SERVICE-SECRET      ▼
       ▼                     ┌───────────────┐
┌──────────────────────┐     │  MySQL 8      │
│  Flask 5001          │     │  smart_attend │
│  /extract-embedding  │     │  -ance        │
│  /health             │     │               │
│  /register (legacy)  │     │  Tables:      │
│  /verify   (legacy)  │     │  users        │
│  YuNet → MiniFASNet  │     │  students     │
│  → SFace embedding   │     │  teachers     │
│                      │     │  subjects     │
└──────────────────────┘     │  attendance_  │
                             │  sessions     │
                             │  attendance_  │
                             │  records      │
                             │  attendance_  │
                             │  verification │
                             │  _attempts    │
                             │  face_embeddings│
                             │  assignments  │
                             │  submissions  │
                             │  feedback_    │
                             │  complaints   │
                             │  email_verif_ │
                             │  otp          │
                             │  password_    │
                             │  reset_otp    │
                             └───────────────┘
```

**Data flow for attendance:**
Android → `POST /api/student/attendance/verification-attempt` → `AttendanceVerificationService.start()` → returns `attemptId`
→ `POST /api/face/verify` (multipart: image + lat/lng/accuracy/mockLocation + attemptId)
→ `GeofenceService.check()` → `FaceVerificationService.verifyStudentFace()` → Flask `/extract-embedding`
→ anti-spoof + embedding → cosine similarity → `AttendanceVerificationService.markSuccessful()` → `AttendanceRecord` saved

---

## 4. REPOSITORY AND MODULE STRUCTURE

```
smart-college-attendance/
├── android-app/app/src/main/java/com/college/attendance/mobile/
│   ├── MainActivity.java              # Login + role routing
│   ├── RegisterActivity.java          # Student/Teacher/HOD registration
│   ├── OtpVerificationActivity.java   # Email OTP verification
│   ├── ForgotPasswordActivity.java    # Password reset step 1
│   ├── ResetOtpVerificationActivity.java
│   ├── SetNewPasswordActivity.java
│   ├── ui/
│   │   ├── DashboardActivity.java     # ALL role dashboards (Student/Teacher/HOD)
│   │   └── FaceCaptureActivity.java   # CameraX + face register/verify
│   ├── api/ApiClient.java             # Retrofit singleton
│   ├── model/                         # DTOs: FaceResponse, VerificationAttempt, ...
│   └── util/LocationHelper.java       # GPS + mock-location detection
│
├── backend/src/main/java/com/college/attendance/
│   ├── AttendanceApplication.java
│   ├── config/SecurityConfig.java     # JWT stateless, RBAC rules
│   ├── controller/                    # 10 controllers (see API inventory)
│   ├── service/                       # 10 services
│   ├── entity/                        # 16 JPA entities
│   ├── repo/                          # 16 Spring Data repositories
│   ├── dto/                           # Request/Response DTOs
│   ├── security/JwtFilter.java        # Bearer token extraction + validation
│   └── util/EncryptionUtil.java       # AES-256-GCM encrypt/decrypt
│
├── face-service/
│   ├── app.py                         # Flask routes + EXIF-aware decode + diagnostics
│   ├── face_utils.py                  # YuNet detection + SFace embedding
│   ├── anti_spoof.py                  # MiniFASNetV2+V1SE ensemble + quality gates
│   ├── models/
│   │   ├── face_detection_yunet_2023mar.onnx
│   │   ├── face_recognition_sface_2021dec.onnx
│   │   ├── 2.7_80x80_MiniFASNetV2.onnx
│   │   └── 4_0_0_80x80_MiniFASNetV1SE.onnx
│   └── tests/
│       ├── test_flask.py
│       ├── test_anti_spoof.py
│       ├── test_face_utils.py
│       └── test_diagnostic.py
│
└── database/schema.sql                # Creates DB + app_info seed only
```

---

## 5. STUDENT MODULE WORKFLOW

### 5.1 Registration
1. `RegisterActivity` → `POST /api/auth/register/student` (name, email, password, rollNumber, course, semester, section)
2. Response: `201 OTP_REQUIRED`
3. `OtpVerificationActivity` → `POST /api/auth/verify-otp` → account activated, JWT not issued yet
4. Student navigates to login

**Email OTP:** When `app.otp.dev-mode=true`, OTP is printed to console (no SMTP required). When `false`, sent via SMTP (Mailtrap/production). OTP expires in 5 minutes, max 5 attempts, 60-second resend cooldown.

### 5.2 Login
1. `MainActivity` → `POST /api/auth/login` → JWT `{token, role, userId}`
2. JWT stored in `SharedPreferences`
3. Role-based routing: role=STUDENT → `DashboardActivity` with student view
4. JWT contains `email` (subject), `uid`, `role`, signed with HS256

### 5.3 Student Dashboard
`GET /api/student/profile` → name, rollNumber, course, semester, section
`GET /api/student/attendance` → list of attendance records
`GET /api/student/attendance/summary` → per-subject totals, attended count, percentage
`GET /api/student/active-session` → current active session for student's section
`GET /api/student/assignments` → assignments for student's section

### 5.4 Attendance Marking Flow
1. Student taps "Mark Attendance" on the dashboard
2. `GET /api/student/active-session` → retrieves session for student's section
3. `POST /api/student/attendance/verification-attempt` `{sessionId}` → `{attemptId, sessionId, status:PENDING, expiresAt}`
4. `DashboardActivity` launches `FaceCaptureActivity` with `attemptId` + `sessionId`
5. `FaceCaptureActivity` starts CameraX preview (front or rear camera selectable)
6. `LocationHelper` retrieves GPS coordinates + mock-location flag
7. Student taps Capture → JPEG saved to temp file
8. Student taps Upload/Verify:
   - `POST /api/face/verify` multipart: `image`, `attemptId`, `latitude`, `longitude`, `accuracy`, `mockLocation`
9. Backend pipeline:
   - `AttendanceVerificationService.requirePending(attemptId)` — sets status to VERIFYING (optimistic version lock)
   - `GeofenceService.check()` — rejects if outside 90m, GPS accuracy >50m, or mock detected
   - `FaceVerificationService.verifyStudentFace(student, imageBytes)`:
     - Retrieve `FaceEmbedding` row (nonce + ciphertext + tag)
     - `EncryptionUtil.decrypt(ct)` → plaintext 128-dim float array
     - `POST http://localhost:5001/extract-embedding` with `X-FACE-SERVICE-SECRET`
     - Flask: `decode_image_upright()` → YuNet detection → `check_liveness()` → MiniFASNet ensemble → SFace embedding
     - If spoof: return `is_real=false`, `rejection_reason`, `liveness_score`
     - If live: return `is_real=true`, `embedding[128]`, `liveness_score`
     - `cosineSimilarity(registered, live)` ≥ 0.60 → matched
   - `AttendanceVerificationService.markSuccessful()` → saves `AttendanceRecord` (PRESENT, lat/lng, distance)
10. Response: `{verified:true, attendanceMarked:true, attendanceId, sessionId, similarity}`

### 5.5 Failure Paths
- **No active session:** `204 No Content` → "No active session"
- **Attempt expired (120s):** `410 Gone`
- **Rate limited:** `429 Too Many Requests`
- **Duplicate attendance:** `409 Conflict`
- **Outside geofence:** `{verified:false, reason:LOCATION, message:...}` → attempt reset to PENDING (retryable)
- **Mock location:** `{verified:false, reason:LOCATION, message:...}`
- **Face not registered:** `{verified:false, reason:FACE, message:Face is not registered}`
- **Blurry image:** `{verified:false, reason:QUALITY, message:...}` → attempt marked FAILED
- **Spoof detected:** `{verified:false, reason:SPOOF}` → attempt marked FAILED
- **Face not matched:** `{verified:false, reason:FACE, similarity:0.xxx}` → attempt marked FAILED
- **Flask down:** HTTP 503

### 5.6 Retake After Failure
`FaceCaptureActivity` calls `startVerification(sessionId)` to fetch a fresh `attemptId` before the next capture. It never reuses a consumed or failed attempt.

### 5.7 Logout
`SharedPreferences.clear()` + `FLAG_ACTIVITY_CLEAR_TASK` → navigates to `MainActivity`, back stack cleared.

---

## 6. TEACHER MODULE WORKFLOW

### 6.1 Authentication
Teacher registers → email OTP → **HOD or Admin approval required** (account stays PENDING until approved, password set by HOD via `POST /api/hod/teachers/{userId}/approve`).

### 6.2 Teacher Dashboard
All functionality below is backed by real API endpoints:

| Feature | API | Android UI |
|---|---|---|
| Profile | `GET /api/teacher/profile` | ✅ via DashboardActivity |
| View subjects | `GET /api/teacher/subjects` | ✅ |
| Create session | `POST /api/teacher/session/create` | ✅ |
| View sessions | `GET /api/teacher/sessions` | ✅ |
| Close session | `POST /api/teacher/session/{id}/close` | ✅ |
| Session attendance | `GET /api/teacher/session/{id}/attendance` | ✅ |
| View students | `GET /api/teacher/students` | ✅ |
| Create assignment | `POST /api/teacher/assignment/create` | ✅ |
| Complaints | `POST /api/academic/complaints` | UI shows toast only |
| Doubts | Static toast "Doubt submission not yet integrated" | ❌ Not implemented |

### 6.3 Session Creation Detail
`POST /api/teacher/session/create` with `{subjectId, section, durationMinutes}`
- Validates subject belongs to teacher
- Validates section matches subject section
- Rejects if active session already exists for that class (409)
- Sets `startTime = now`, `endTime = now + durationMinutes`, `active = true`

### 6.4 Development-only endpoints
`/api/dev/session/**` — available only when Spring profile `dev` is active. These let a developer create a session without teacher credentials. These are **not visible in the production build** and **not shown in the teacher Android UI**.

---

## 7. HOD MODULE WORKFLOW

### 7.1 Authentication
HOD registers → email OTP → **Admin approval** required. Admin sets credentials via `AdminController`.

### 7.2 HOD Dashboard — Implemented and Backed by Real APIs

| Feature | API | Status |
|---|---|---|
| View all students | `GET /api/hod/students` | ✅ COMPLETE |
| View all teachers | `GET /api/hod/teachers` | ✅ COMPLETE |
| View all attendance | `GET /api/hod/attendance` | ✅ COMPLETE — returns all records |
| Aggregate analytics | `GET /api/hod/analytics` | ⚠️ PARTIAL — returns student count + attendance_records count + note; no percentage |
| View complaints | `GET /api/hod/complaints` | ✅ COMPLETE |
| Disable user | `POST /api/hod/user/{id}/disable` | ✅ COMPLETE |
| Pending teachers | `GET /api/hod/teachers/pending` | ✅ COMPLETE |
| Approve teacher | `POST /api/hod/teachers/{userId}/approve` | ✅ COMPLETE |
| Resolve complaint | `POST /api/academic/complaints/{id}/resolve` | ✅ COMPLETE |

### 7.3 HOD gaps
- **No subject management endpoint** — subjects must be seeded directly into the database.
- **No attendance search/filter** — `GET /api/hod/attendance` returns ALL records; no filter by date, subject, section, or student.
- **Analytics** — only raw counts; no percentage, no subject-level breakdown.
- **No HOD profile endpoint** — HOD sees the Admin profile UI (Android `DashboardActivity` has HOD branch but it calls student or teacher endpoints — not a dedicated HOD profile).

---

## 8. FACE REGISTRATION AND VERIFICATION (TECHNICAL DETAIL)

### 8.1 Face Registration

**Android:**
1. `FaceCaptureActivity` opened with `register=true` from `DashboardActivity.registerFace()`
2. CameraX captures JPEG to temp file → reads back as byte array
3. `POST /api/face/register` multipart: `image` field, Bearer JWT

**Spring Boot — `FaceRegistrationController`:**
1. `@PreAuthorize("hasRole('STUDENT')")` — only students can register
2. Validates image not empty
3. Calls `FaceRegistrationService.registerFace(email, imageBytes)`:
   - Resolves email → User → Student
   - Guards: active + APPROVED status
   - Duplicate guard: `embeddingRepo.existsByStudent_Id()` → 409 if already registered
   - `callFlaskExtract(imageBytes)` → `POST http://localhost:5001/extract-embedding` with header `X-FACE-SERVICE-SECRET`
   - Validates embedding: 128 floats, all finite
   - Encodes to 512 bytes (big-endian IEEE-754)
   - `EncryptionUtil.encrypt(plain)` → 12-byte nonce + ciphertext + 16-byte GCM tag
   - Saves `FaceEmbedding` row (nonce, ciphertext, tag, `encryption_version="v1"`, timestamps)

**Flask — `/extract-embedding`:**
1. `_check_secret()` — `hmac.compare_digest` against `FACE_SERVICE_SECRET` env var
2. Read image bytes → `np.frombuffer`
3. `decode_image_upright()` — `cv2.IMREAD_IGNORE_ORIENTATION` + manual EXIF rotation (deterministic)
4. `face_utils.detect_single_face_with_box(img)` — YuNet (score threshold 0.6, NMS 0.3) → exactly one face required
5. `anti_spoof.check_liveness(img, bbox)`:
   - Quality gate: face-region blur (Laplacian var ≥50 at 100×100 normalised), brightness 15–245, face ratio ≥0.03
   - If quality fails → `{is_real:false, liveness_score:0.0, rejection_reason:BLURRY_IMAGE/...}`
   - Crop at scale 2.7 (MiniFASNetV2) and 4.0 (MiniFASNetV1SE) → resize to 80×80 → float32 [0,255] NHWC
   - Both ONNX models contain leading Transpose(NHWC→NCHW) + softmax; no external preprocessing
   - Ensemble: `pred1 + pred2` → argmax → class 1 = real face
   - `is_real = (argmax==1) AND (ensemble[1]/2 >= 0.65)`
   - If spoof → `{is_real:false, liveness_score:0.xxxx, rejection_reason:SPOOF_DETECTED}`
6. If live → `SFACE_NET.alignCrop(img, face_info)` → `SFACE_NET.feature(aligned)` → 128-dim float array
7. Returns `{extracted:true, is_real:true, liveness_score:0.xxxx, embedding:[f0..f127]}`

**On registration** Flask returns embedding, Spring encrypts and stores. Original image not stored. Plaintext embedding not stored.

### 8.2 Face Verification

Additional steps vs registration:
1. Retrieve `FaceEmbedding` from MySQL by `student_id`
2. `EncryptionUtil.decrypt(nonce, ciphertext, tag)` → 512 bytes → 128 float array
3. Flask extracts live embedding (same pipeline)
4. `cosineSimilarity(registered, live)` — standard dot-product / (||v1|| × ||v2||)
5. `similarity >= 0.60` → match; `< 0.60` → no match

### 8.3 Anti-Spoofing Models

| Property | MiniFASNetV2 | MiniFASNetV1SE |
|---|---|---|
| Filename | `2.7_80x80_MiniFASNetV2.onnx` | `4_0_0_80x80_MiniFASNetV1SE.onnx` |
| SHA-256 verified | Yes | Yes |
| Crop scale | 2.7× | 4.0× |
| Input | NHWC [1,80,80,3] float32 [0,255] | Same |
| Output | 3-class softmax [print, real, replay] | Same |
| Class 1 | real_face | real_face |

Ensemble: sum both probability vectors, argmax of sum, liveness_score = sum[1] / 2.

---

## 9. GEOFENCING

**Configuration (application.properties):**
- Centre: `28.417341°N, 77.523483°E` (ICT Building)
- Radius: 90 m
- Max GPS accuracy: 50 m

**Pipeline (GeofenceService):**
1. `geofence.enabled=true` gate — if false, all checks pass (dev convenience)
2. Validate coordinate finite + non-null
3. `mockLocation == true` → reject: "Mock/fake location detected"
4. `accuracy > 50m` → reject: "GPS signal too weak (±Xm, need ±50m)"
5. `GeoUtils.distanceMeters(lat, lng, 28.417341, 77.523483)` — Haversine formula
6. `distance > 90m` → reject: "You are about Xm from the ICT Building"
7. Pass → `{allowed:true, distanceMeters:xx.x}`

**Android side (`LocationHelper`):**
- Uses `LocationManager` (GPS + Network providers)
- Detects mock location via `Location.isFromMockProvider()` (API 18+)
- Passes `mockLocation: true` to backend if detected

**Location rejection is retryable** — `GeofenceService` result `allowed=false` resets the attempt to PENDING, allowing the student to fix GPS and retry without consuming the attempt.

---

## 10. DATABASE DESIGN

### Entity Inventory

| Entity | Table | Key Fields | Relationships |
|---|---|---|---|
| `User` | `users` | id, email (unique), password (BCrypt), name, role, phone, active, approvalStatus, emailVerified | Base for Student and Teacher |
| `Student` | `students` | id, rollNumber (unique), course, semester, section, faceImagePath | @OneToOne User |
| `Teacher` | `teachers` | id, employeeId (unique), department | @OneToOne User |
| `Subject` | `subjects` | id, code, name, section, semester | @ManyToOne Teacher |
| `AttendanceSession` | `attendance_sessions` | id, section, startTime, endTime, active | @ManyToOne Subject, Teacher |
| `AttendanceRecord` | `attendance_records` | id, markedAt, status, verificationMethod, latitude, longitude, accuracyMeters, distanceMeters | @ManyToOne Session, Student; UNIQUE(session_id, student_id) |
| `AttendanceVerificationAttempt` | `attendance_verification_attempts` | id (UUID), createdAt, expiresAt, completedAt, status (PENDING/VERIFYING/SUCCEEDED/FAILED/EXPIRED), @Version | @ManyToOne Student, Session; indexed on student+session, expiresAt |
| `FaceEmbedding` | `face_embeddings` | id, nonce (12B), ciphertext (VARBINARY 4096), tag (16B), encryptionVersion, createdAt, updatedAt | @OneToOne Student |
| `Assignment` | `assignments` | id, title, description, dueDate | @ManyToOne Subject, Teacher |
| `Submission` | `submissions` | id, submittedAt, content | @ManyToOne Assignment, Student |
| `FeedbackComplaint` | `feedback_complaints` | id, type, subject, message, status, resolution, createdAt, resolvedAt | @ManyToOne raisedBy (User) |
| `EmailVerificationOtp` | `email_verification_otps` | id, otp, expiresAt, verified, attemptCount | @ManyToOne or email FK |
| `PasswordResetOtp` | `password_reset_otps` | id, resetToken, expiresAt, used | email FK |
| `Role` | enum | STUDENT, TEACHER, HOD, ADMIN | — |
| `ApprovalStatus` | enum | APPROVED, PENDING, REJECTED | — |
| `VerificationAttemptStatus` | enum | PENDING, VERIFYING, SUCCEEDED, FAILED, EXPIRED | — |

### Key Constraints
- `attendance_records` has `UNIQUE(session_id, student_id)` — one attendance per student per session, enforced at DB level
- `face_embeddings` has `UNIQUE(student_id)` — one face embedding per student
- `teachers.employeeId` UNIQUE
- `students.rollNumber` UNIQUE
- `users.email` UNIQUE

---

## 11. API INVENTORY

| Method | Endpoint | Controller | Role | Purpose | Status |
|---|---|---|---|---|---|
| POST | `/api/auth/login` | AuthController | Public | JWT login | ✅ |
| POST | `/api/auth/register/student` | AuthController | Public | Student registration | ✅ |
| POST | `/api/auth/register/teacher` | AuthController | Public | Teacher registration | ✅ |
| POST | `/api/auth/register/hod` | AuthController | Public | HOD registration | ✅ |
| POST | `/api/auth/verify-otp` | AuthController | Public | Email OTP verification | ✅ |
| POST | `/api/auth/resend-otp` | AuthController | Public | Resend OTP | ✅ |
| POST | `/api/auth/forgot-password` | AuthController | Public | Password reset step 1 | ✅ |
| POST | `/api/auth/verify-reset-otp` | AuthController | Public | Password reset step 2 | ✅ |
| POST | `/api/auth/reset-password` | AuthController | Public | Password reset step 3 | ✅ |
| GET | `/api/health` | HealthController | Public | Health check | ✅ |
| GET | `/api/student/profile` | StudentController | STUDENT | Student profile | ✅ |
| GET | `/api/student/attendance` | StudentController | STUDENT | Attendance history | ✅ |
| GET | `/api/student/attendance/summary` | StudentController | STUDENT | Per-subject summary | ✅ |
| GET | `/api/student/active-session` | StudentController | STUDENT | Current active session | ✅ |
| POST | `/api/student/attendance/verification-attempt` | StudentController | STUDENT | Start attempt | ✅ |
| POST | `/api/student/attendance/mark` | StudentController | STUDENT | Direct mark (blocked) | ⛔ Always 403 |
| GET | `/api/student/assignments` | StudentController | STUDENT | View assignments | ✅ |
| POST | `/api/face/register` | FaceRegistrationController | STUDENT | Register face | ✅ |
| POST | `/api/face/verify` | FaceController | STUDENT | Verify face + mark attendance | ✅ |
| GET | `/api/teacher/profile` | TeacherController | TEACHER | Teacher profile | ✅ |
| GET | `/api/teacher/subjects` | TeacherController | TEACHER | Assigned subjects | ✅ |
| POST | `/api/teacher/session/create` | TeacherController | TEACHER | Create session | ✅ |
| POST | `/api/teacher/session/{id}/close` | TeacherController | TEACHER | Close session | ✅ |
| GET | `/api/teacher/sessions` | TeacherController | TEACHER | Session history | ✅ |
| GET | `/api/teacher/session/{id}/attendance` | TeacherController | TEACHER | Session attendance | ✅ |
| GET | `/api/teacher/students` | TeacherController | TEACHER | Students in sections | ✅ |
| POST | `/api/teacher/assignment/create` | TeacherController | TEACHER | Create assignment | ✅ |
| GET | `/api/hod/students` | HodController | HOD | All students | ✅ |
| GET | `/api/hod/teachers` | HodController | HOD | All teachers | ✅ |
| GET | `/api/hod/attendance` | HodController | HOD | All attendance records | ✅ |
| GET | `/api/hod/analytics` | HodController | HOD | Aggregate counts | ⚠️ Partial |
| GET | `/api/hod/complaints` | HodController | HOD | All complaints | ✅ |
| POST | `/api/hod/user/{id}/disable` | HodController | HOD | Disable user | ✅ |
| GET | `/api/hod/teachers/pending` | HodController | HOD | Pending approval list | ✅ |
| POST | `/api/hod/teachers/{userId}/approve` | HodController | HOD | Approve + set password | ✅ |
| GET | `/api/academic/assignments` | AcademicController | STUDENT/TEACHER | Assignments | ✅ |
| POST | `/api/academic/assignments` | AcademicController | TEACHER | Create assignment | ✅ |
| POST | `/api/academic/assignments/{id}/submit` | AcademicController | STUDENT | Submit assignment | ✅ |
| GET | `/api/academic/submissions` | AcademicController | STUDENT/TEACHER | View submissions | ✅ |
| POST | `/api/academic/complaints` | AcademicController | Authenticated | File complaint | ✅ |
| GET | `/api/academic/complaints/mine` | AcademicController | Authenticated | My complaints | ✅ |
| POST | `/api/academic/complaints/{id}/resolve` | AcademicController | HOD | Resolve complaint | ✅ |
| POST | `/api/admin/**` | AdminController | ADMIN | Admin functions | ✅ |
| POST | `/api/dev/session/create` | DevAttendanceSessionController | Public (dev profile only) | Dev session | ⚠️ Dev only |
| POST | `/api/dev/session/{id}/close` | DevAttendanceSessionController | Public (dev profile only) | Dev close | ⚠️ Dev only |
| POST | `/api/dev/session/close-active` | DevAttendanceSessionController | Public (dev profile only) | Dev close all | ⚠️ Dev only |
| GET | `/api/dev/session/active` | DevAttendanceSessionController | Public (dev profile only) | Dev query | ⚠️ Dev only |
| GET | `/health` (Flask) | — | Internal | Flask health | ✅ |
| POST | `/register` (Flask) | — | Internal (legacy) | Legacy ORB-based register | ⚠️ Legacy |
| POST | `/verify` (Flask) | — | Internal (legacy) | Legacy ORB-based verify | ⚠️ Legacy |
| POST | `/extract-embedding` (Flask) | — | Internal (`X-FACE-SERVICE-SECRET`) | Production embedding+liveness | ✅ |

---

## 12. SECURITY ARCHITECTURE

### Password Hashing
BCryptPasswordEncoder — salted adaptive hash. Never stored or logged in plaintext.

### JWT Authentication
- Library: `jjwt`
- Algorithm: HS256
- Payload claims: `sub` (email), `uid` (userId), `role`
- Expiry: 24 hours
- Stateless (no session): `SessionCreationPolicy.STATELESS`
- `JwtFilter` extracts Bearer token from `Authorization` header, calls `JwtService.parse()`, sets `SecurityContextHolder` authentication

### Role-Based Access Control
Enforced at two levels:
1. `SecurityConfig.filterChain()` — URL-level: `/api/student/**` and `/api/face/**` require `ROLE_STUDENT`, `/api/teacher/**` requires `ROLE_TEACHER`, `/api/hod/**` requires `ROLE_HOD`
2. `@PreAuthorize` annotations on specific methods (e.g., `FaceRegistrationController` uses `@PreAuthorize("hasRole('STUDENT')")`)

### AES-256-GCM Embedding Encryption
- Key source: `FACE_EMB_KEY` env var (Base64-encoded 32 bytes) or Java system property `face.emb.key`
- Nonce: 12 bytes, random per encryption
- Authentication tag: 128 bits
- Storage: nonce + ciphertext + tag in separate DB columns (VARBINARY)
- Key never logged, never returned in API response

### Internal Flask Authentication
`hmac.compare_digest(provided, configured)` — constant-time comparison prevents timing attacks. Flask fails closed if `FACE_SERVICE_SECRET` env var is not set.

### Single-Use Verification Attempts
- Each attempt has status: PENDING → VERIFYING → SUCCEEDED/FAILED/EXPIRED
- `@Version` optimistic lock prevents race conditions
- `findWithLockById()` uses `@Lock(PESSIMISTIC_WRITE)` during status transitions
- Attempt expires after 120 seconds
- Cannot be reused once VERIFYING (unless location rejection → back to PENDING)

### Duplicate Attendance
`UNIQUE(session_id, student_id)` DB constraint + application-level `rejectDuplicate()` check in `AttendanceVerificationService`.

### Rate Limiting
5 attempts per student per 10-minute window (counted in `AttendanceVerificationService.start()`).

### Mock Location Detection
Android `LocationHelper` detects `Location.isFromMockProvider()`. Backend `GeofenceService` checks `mockLocation == true` → rejects immediately.

---

## 13. MINOR PROJECT MILESTONE VERIFICATION

### Milestone 1 — User Authentication
**Status: COMPLETE**

| Check | Status |
|---|---|
| Student login via POST /api/auth/login | ✅ Implemented and testable |
| Teacher login | ✅ Implemented (requires HOD approval first) |
| HOD login | ✅ Implemented (requires Admin approval first) |
| JWT token generated with role | ✅ Verified in JwtService.generate() |
| JWT validated on every request | ✅ JwtFilter.java runs for all /api/** except /api/auth/** |
| Role-specific routing in Android | ✅ DashboardActivity branches on role |
| Invalid credentials rejected (HTTP 401) | ✅ AuthService throws, AuthController returns 401 |
| Logout clears session | ✅ SharedPreferences.clear() + FLAG_ACTIVITY_CLEAR_TASK |
| Email OTP verification required for activation | ✅ emailVerified flag gate in AuthService |
| Profile access per role | ✅ /api/student/profile, /api/teacher/profile |
| Password reset (3-step OTP flow) | ✅ Implemented |

**Missing evidence:** Manual device testing screenshots.

---

### Milestone 2 — Role-Based Access Control
**Status: COMPLETE**

| Check | Status |
|---|---|
| Student cannot access /api/teacher/** | ✅ SecurityConfig URL rules, returns 403 |
| Teacher cannot access /api/student/** | ✅ SecurityConfig URL rules, returns 403 |
| HOD cannot access /api/student/** or /api/teacher/** | ✅ SecurityConfig URL rules, returns 403 |
| Unauthenticated → 401 on /api/** | ✅ JwtFilter + AuthenticationEntryPoint |
| /api/face/** STUDENT only | ✅ SecurityConfig |
| Teacher owns session guard | ✅ TeacherController checks teacher.getId() |
| /api/academic/** with method-level @PreAuthorize | ✅ Implemented |

**Missing evidence:** Automated test for 403 on cross-role access. Manual testing screenshots.

---

### Milestone 3 — Teacher Creates Attendance Session
**Status: COMPLETE**

| Check | Status |
|---|---|
| Teacher can create session via Android UI | ✅ DashboardActivity.createSession() calls POST /api/teacher/session/create |
| Subject and section validated | ✅ Subject must belong to teacher; section must match subject section |
| Start time and end time set | ✅ startTime = now, endTime = now + durationMinutes |
| Active flag set to true | ✅ .active(true) in builder |
| Duplicate active session blocked (409) | ✅ findFirstBySubjectAndSectionAndActiveTrue check |
| Session closure via Android UI | ✅ DashboardActivity.closeSession() calls POST /api/teacher/session/{id}/close |
| Session closure sets active=false and endTime=now | ✅ Verified in TeacherController.close() |
| Student retrieves active session | ✅ GET /api/student/active-session |

**Dev-only note:** `/api/dev/session/create` exists for testing only and is behind `@Profile("dev")`. Not accessible in production builds.

---

### Milestone 4 — Student Marks Attendance (Face / Fingerprint)
**Status: COMPLETE (face) + NOT IMPLEMENTED (fingerprint)**

**Face:**

| Check | Status |
|---|---|
| Face registration via CameraX | ✅ FaceCaptureActivity + POST /api/face/register |
| EXIF-aware decode | ✅ decode_image_upright() in app.py |
| YuNet face detection (exactly one face) | ✅ detect_single_face_with_box() |
| Anti-spoofing (MiniFASNetV2+V1SE ensemble) | ✅ check_liveness() — threshold 0.65 |
| Quality gate (blur/brightness/face size) | ✅ _quality_metrics() + _decide_quality() |
| SFace 128-dim embedding extraction | ✅ SFACE_NET.alignCrop() + feature() |
| AES-256-GCM encryption before storage | ✅ EncryptionUtil.encrypt() |
| Duplicate registration blocked | ✅ existsByStudent_Id() check + DB UNIQUE |
| Verification attempt lifecycle | ✅ PENDING → VERIFYING → SUCCEEDED/FAILED |
| Geofence validation before face check | ✅ GeofenceService.check() first in FaceController |
| Mock location detection | ✅ Android LocationHelper + backend check |
| Cosine similarity matching (threshold 0.60) | ✅ cosineSimilarity() |
| Attendance record created on success | ✅ AttendanceVerificationService.markSuccessful() |
| Rate limiting (5/10 min) | ✅ countByStudentAndCreatedAtAfter() |
| Replay protection (single-use attempt) | ✅ status machine + @Version |

**Fingerprint:**
**NOT IMPLEMENTED.** No fingerprint attendance code exists in the Android app, Spring Boot, or anywhere in the project. The Android device may use biometric unlock for the OS, but this is completely separate from application-level fingerprint attendance marking. There is no `BiometricPrompt`, `FingerprintManager`, or fingerprint-related API call anywhere in the codebase.

---

### Milestone 5 — Attendance Stored in Database
**Status: COMPLETE**

| Check | Status |
|---|---|
| AttendanceRecord entity mapped to attendance_records table | ✅ |
| UNIQUE(session_id, student_id) constraint | ✅ Declared on entity + enforced at DB |
| Successful verification creates exactly one record | ✅ markSuccessful() saves one AttendanceRecord |
| Failed verification creates no record | ✅ markFailed() only sets status=FAILED on attempt, no record created |
| Record includes student, session, markedAt, status, lat/lng, distance | ✅ All fields present |
| FaceEmbedding stored as nonce+ciphertext+tag | ✅ |
| AttendanceVerificationAttempt tracked separately | ✅ |
| Session table includes teacher, subject, section, times, active | ✅ |

---

### Milestone 6 — View Attendance
**Status: COMPLETE (Student + Teacher) / PARTIAL (HOD)**

| View | API | Android | Status |
|---|---|---|---|
| Student attendance history | GET /api/student/attendance | ✅ DashboardActivity.studentAttendance() | COMPLETE |
| Student attendance summary (per-subject %) | GET /api/student/attendance/summary | ✅ DashboardActivity.loadStudentOverview() | COMPLETE |
| Teacher session attendance | GET /api/teacher/session/{id}/attendance | ✅ DashboardActivity.sessionAttendance() | COMPLETE |
| HOD all attendance | GET /api/hod/attendance | ✅ DashboardActivity.analytics() calls this | COMPLETE |
| HOD attendance by subject/date/section | — | — | NOT IMPLEMENTED — no filter parameters |

---

### Milestone 7 — Basic Search
**Status: PARTIALLY COMPLETE**

| Feature | Status |
|---|---|
| HOD student search | ⚠️ HOD can call GET /api/hod/students → returns all; no search param |
| Teacher student view | ⚠️ GET /api/teacher/students → returns all students in teacher's sections; no search |
| Android-side filter | ❌ No search box or filter UI in DashboardActivity |
| Subject/session search | ❌ Not implemented |

**Conclusion:** The backend returns lists that could be filtered client-side, but there is no actual search parameter supported by any endpoint, and no search UI in the Android app. This milestone is **NOT IMPLEMENTED** as a true search feature. The closest thing is `GET /api/student/active-session` which is filtered by section, but that is not a user search — it is a system lookup.

---

### Milestone 8 — Basic Dashboard
**Status: COMPLETE (Student, Teacher) / PARTIALLY COMPLETE (HOD)**

| Dashboard | Real data displayed | Status |
|---|---|---|
| Student | Profile, active session, attendance records, attendance % summary per subject, assignments | COMPLETE — all from live API |
| Teacher | Profile, subjects, sessions list, session attendance, students list, assignments | COMPLETE — all from live API |
| HOD | Student list, teacher list, all attendance records, pending teacher approvals, aggregate counts | PARTIALLY COMPLETE — analytics shows raw counts only; no percentage, no chart |

---

## 14. TESTING INVENTORY AND RESULTS

### 14.1 Automated Test Counts (from code inspection)

**Flask (pytest):**

| Test file | Tests | Coverage area |
|---|---|---|
| test_flask.py | ~60+ | /extract-embedding route, liveness, quality gates, error paths |
| test_anti_spoof.py | ~20+ | MiniFASNet ensemble, quality gates, threshold |
| test_face_utils.py | ~15+ | YuNet detection, SFace embedding |
| test_diagnostic.py | ~12+ | EXIF parsing, orientation values, malformed JPEG |
| **Total** | **~106** | Full Flask pipeline |

Last confirmed run result (from session memory, 2026-11-22): **106 passed, 0 failed.**

**Spring Boot (Maven):**

| Test class | Tests | Coverage area |
|---|---|---|
| FaceControllerTest | 12+ | Face verify endpoint, liveness paths |
| EncryptionUtilTest | Multiple | AES-256-GCM round trip |
| **Total** | **~21+** | Backend critical paths |

**Android:**
No test sources exist under `android-app/app/src/test/` or `androidTest/`. Only `main/` exists. **Zero automated Android tests.** Build verification via `gradlew :app:compileDebugJavaWithJavac` — last result: `BUILD SUCCESSFUL`.

### 14.2 Test Inventory

| Test ID | Feature | Scenario | Type | Status |
|---|---|---|---|---|
| T01 | Face registration | Valid JPEG, one face, liveness pass | Automated (Flask) | PASSED |
| T02 | Face registration | No face in image → NO_FACE | Automated (Flask) | PASSED |
| T03 | Face registration | Multiple faces → MULTIPLE_FACES | Automated (Flask) | PASSED |
| T04 | Face registration | Missing image field → 400 | Automated (Flask) | PASSED |
| T05 | Face registration | Blurry image → BLURRY_IMAGE | Automated (Flask) | PASSED |
| T06 | Anti-spoofing | Genuine face ensemble score ≥ 0.65 | Automated (Flask) | PASSED |
| T07 | Anti-spoofing | Spoof: argmax ≠ 1 → SPOOF_DETECTED | Automated (Flask) | PASSED |
| T08 | Anti-spoofing | Quality gate: blur < threshold | Automated (Flask) | PASSED |
| T09 | Anti-spoofing | Quality gate: underexposed | Automated (Flask) | PASSED |
| T10 | Encryption | AES-256-GCM round trip, 128 floats | Automated (Backend) | PASSED |
| T11 | Face controller | POST /verify: liveness fail → not marked | Automated (Backend) | PASSED |
| T12 | Face controller | POST /verify: spoof detected response | Automated (Backend) | PASSED |
| T13 | EXIF parsing | Orientation 1-8 all values | Automated (Flask) | PASSED |
| T14 | EXIF parsing | Malformed JPEG → returns 1 safely | Automated (Flask) | PASSED |
| T15 | Liveness threshold | Genuine 0.7581 ≥ 0.65 → accepted | Automated (Flask) | PASSED |
| T16 | Liveness threshold | Spoof 0.44 < 0.65 → rejected | Automated (Flask) | PASSED |
| T17 | Geofence | Mock location detected → rejected | Manual — NOT YET TESTED on device |
| T18 | Geofence | Outside radius → rejected | Manual — NOT YET TESTED on device |
| T19 | Geofence | GPS accuracy > 50m → rejected | Manual — NOT YET TESTED on device |
| T20 | Authentication | Invalid password → 401 | Manual — NOT YET TESTED |
| T21 | Authentication | Student accesses /api/teacher/ → 403 | Manual — NOT YET TESTED |
| T22 | Attendance | Full end-to-end: session → verify → record | Manual device test — TESTED (session memory confirms genuine capture at 0.7581 passed) |
| T23 | Attendance | Duplicate marking → 409 | Manual — NOT YET TESTED formally |
| T24 | Fingerprint | Application-level fingerprint attendance | NOT IMPLEMENTED — cannot be tested |

### 14.3 Manual Tests Not Yet Performed
- Login screenshots for all three roles
- Teacher creating a session on device
- Student marking attendance on device end-to-end screenshot capture
- Geofence rejection on device
- Printed photo and screen replay rejection screenshots
- HOD approving a teacher

---

## 15. EXISTING IMPLEMENTATION EVIDENCE

- Flask test suite: **106 passed** (verified 2026-11-22, session memory)
- Backend tests: **~21 passed** (verified 2026-11-22, session memory)
- Android compile: **BUILD SUCCESSFUL** (`gradlew :app:compileDebugJavaWithJavac`, verified 2026-11-21)
- Genuine face liveness score measured on Android device: **0.7581** (above threshold 0.65)
- Screen replay spoof max measured: **0.44** (below threshold 0.65)
- Printed paper spoof max measured: **~0.01-0.02**

---

## 16. REMAINING FEATURES AND GAPS

| Feature | Gap | Priority |
|---|---|---|
| Fingerprint attendance | Not implemented anywhere | Low — mention as "planned" |
| HOD analytics | Returns raw counts only; no percentage | Low |
| Search functionality | No filter parameters on any list endpoint | Medium — add query param |
| HOD profile endpoint | No dedicated HOD profile API | Low |
| HOD department-level reports | Not implemented | Low |
| Subject management | No endpoint; subjects must be DB-seeded | Medium |
| Active liveness | Challenge-response not implemented | Out of scope (passive anti-spoof is implemented) |
| Android unit tests | Zero test files | Medium |
| Teacher doubts | Toast only, no backend | Low |
| Big-endian EXIF test | No MM-byte-order JPEG test fixture | Low |

---

## 17. MERMAID DIAGRAMS

### Diagram 1 — Overall System Architecture

```mermaid
graph TB
    subgraph Android["Android App"]
        MA[MainActivity\nLogin] --> DA[DashboardActivity\nStudent/Teacher/HOD]
        MA --> RA[RegisterActivity]
        DA --> FCA[FaceCaptureActivity\nCameraX]
    end
    subgraph SpringBoot["Spring Boot :8080"]
        AC[AuthController] --> AS[AuthService]
        SC[StudentController] --> AVS[AttendanceVerificationService]
        FC[FaceController] --> FVS[FaceVerificationService]
        FRC[FaceRegistrationController] --> FRS[FaceRegistrationService]
        TC[TeacherController]
        HC[HodController]
        JF[JwtFilter] --> SC & FC & TC & HC & FRC
        FVS --> GS[GeofenceService]
        FRS --> EU[EncryptionUtil\nAES-256-GCM]
        FVS --> EU
    end
    subgraph Flask["Flask Face Service :5001"]
        EE[/extract-embedding] --> FU[face_utils\nYuNet + SFace]
        EE --> ASP[anti_spoof\nMiniFASNetV2+V1SE]
    end
    subgraph MySQL["MySQL 8"]
        DB[(smart_attendance)]
    end

    Android -->|HTTPS + JWT Bearer| SpringBoot
    FVS & FRS -->|HTTP + X-FACE-SERVICE-SECRET| Flask
    SpringBoot -->|JDBC| MySQL
```

### Diagram 2 — Authentication Flow

```mermaid
sequenceDiagram
    participant A as Android
    participant B as Spring Boot
    participant DB as MySQL

    A->>B: POST /api/auth/login {email, password}
    B->>DB: findByEmail(email)
    DB-->>B: User row
    B->>B: BCrypt.matches(password, hash)
    alt Password correct AND emailVerified AND active AND APPROVED
        B->>B: JwtService.generate(userId, email, role)
        B-->>A: 200 {token, role, userId}
        A->>A: Save to SharedPreferences
    else
        B-->>A: 401 {message: "Invalid credentials"}
    end

    Note over A,B: Subsequent requests
    A->>B: GET /api/student/profile\nAuthorization: Bearer <token>
    B->>B: JwtFilter: parse + validate JWT
    B->>B: Set SecurityContextHolder (email, role)
    B-->>A: 200 Student profile
```

### Diagram 3 — Face Registration Flow

```mermaid
sequenceDiagram
    participant A as Android
    participant SB as Spring Boot
    participant F as Flask
    participant DB as MySQL

    A->>A: CameraX captures JPEG
    A->>SB: POST /api/face/register\nmultipart: image\nBearer JWT (STUDENT)
    SB->>SB: Resolve email → Student
    SB->>DB: existsByStudent_Id()?
    alt Already registered
        SB-->>A: 409 Conflict
    else Not registered
        SB->>F: POST /extract-embedding\nX-FACE-SERVICE-SECRET
        F->>F: _check_secret() hmac.compare_digest
        F->>F: decode_image_upright() EXIF-aware
        F->>F: YuNet detect → exactly one face
        F->>F: check_liveness() quality + MiniFASNet ensemble
        alt Liveness fails
            F-->>SB: 200 {extracted:false, is_real:false}
            SB-->>A: 400 "Face image could not be processed"
        else Liveness passes
            F->>F: SFace alignCrop + feature → 128 floats
            F-->>SB: 200 {extracted:true, embedding:[...128...]}
            SB->>SB: Validate 128 floats finite
            SB->>SB: Encode to 512 bytes IEEE-754
            SB->>SB: EncryptionUtil.encrypt() AES-256-GCM
            SB->>DB: Save FaceEmbedding(nonce, ciphertext, tag)
            SB-->>A: 200 {registered:true}
        end
    end
```

### Diagram 4 — Face Verification + Attendance Flow

```mermaid
sequenceDiagram
    participant A as Android
    participant SB as Spring Boot
    participant F as Flask
    participant DB as MySQL

    A->>SB: GET /api/student/active-session
    SB->>DB: findFirstBySection...Active
    SB-->>A: 200 {sessionId, ...}

    A->>SB: POST /api/student/attendance/verification-attempt\n{sessionId}
    SB->>SB: Rate limit check (5/10min)
    SB->>SB: validateSession + rejectDuplicate
    SB->>DB: Save attempt PENDING
    SB-->>A: {attemptId, expiresAt:+120s}

    A->>A: CameraX capture JPEG
    A->>A: LocationHelper GPS + mock check
    A->>SB: POST /api/face/verify\nmultipart: image, attemptId,\nlat, lng, accuracy, mockLocation

    SB->>SB: requirePending(attemptId) → VERIFYING
    SB->>SB: GeofenceService.check()

    alt Outside geofence / mock location
        SB->>DB: resetAfterRejectedLocation → PENDING
        SB-->>A: {verified:false, reason:LOCATION}
    else Inside geofence
        SB->>DB: Get FaceEmbedding(nonce, ciphertext, tag)
        SB->>SB: AES-256-GCM decrypt → 128 floats (registered)
        SB->>F: POST /extract-embedding\nX-FACE-SERVICE-SECRET
        F->>F: decode_image_upright + YuNet + MiniFASNet + SFace
        F-->>SB: {extracted, is_real, liveness_score, embedding}

        alt Spoof / quality fail
            SB->>DB: attempt FAILED
            SB-->>A: {verified:false, reason:SPOOF/QUALITY}
        else Liveness passes
            SB->>SB: cosineSimilarity(registered, live)
            alt similarity < 0.60
                SB->>DB: attempt FAILED
                SB-->>A: {verified:false, reason:FACE, similarity}
            else similarity ≥ 0.60
                SB->>DB: attempt SUCCEEDED\nAttendanceRecord(PRESENT, lat, lng, distance)
                SB-->>A: {verified:true, attendanceMarked:true, attendanceId}
            end
        end
    end
```

### Diagram 5 — Geofence Validation

```mermaid
flowchart TD
    A[Receive lat/lng/accuracy/mockLocation] --> B{geofence.enabled?}
    B -- No --> PASS[Allow]
    B -- Yes --> C{lat/lng valid?}
    C -- No --> FAIL1[Reject: location required]
    C -- Yes --> D{mockLocation == true?}
    D -- Yes --> FAIL2[Reject: mock location]
    D -- No --> E{accuracy > 50m?}
    E -- Yes --> FAIL3[Reject: GPS too weak]
    E -- No --> F[Haversine distance to 28.4173N,77.5234E]
    F --> G{distance > 90m?}
    G -- Yes --> FAIL4[Reject: Xm from ICT Building]
    G -- No --> PASS2[Allow: inside zone]
```

### Diagram 6 — Use Case Diagram

```mermaid
flowchart LR
    subgraph Student
        S1[Register + OTP verify]
        S2[Login]
        S3[View profile]
        S4[View active session]
        S5[Start verification attempt]
        S6[Capture face photo]
        S7[Mark attendance]
        S8[View attendance history]
        S9[View attendance summary]
        S10[View assignments]
        S11[Submit assignment]
        S12[File complaint]
        S13[Register face]
        S14[Logout]
    end
    subgraph Teacher
        T1[Register + OTP + HOD approval]
        T2[Login]
        T3[View profile]
        T4[View subjects]
        T5[Create attendance session]
        T6[Close session]
        T7[View session attendance]
        T8[View students]
        T9[Create assignment]
        T10[View submissions]
        T11[Logout]
    end
    subgraph HOD
        H1[Register + OTP + Admin approval]
        H2[Login]
        H3[View all students]
        H4[View all teachers]
        H5[View all attendance]
        H6[View analytics]
        H7[Approve teacher registration]
        H8[Disable user]
        H9[View complaints]
        H10[Resolve complaint]
        H11[Logout]
    end
```

### Diagram 7 — Database ER Diagram

```mermaid
erDiagram
    users {
        BIGINT id PK
        VARCHAR email UK
        VARCHAR password
        VARCHAR name
        ENUM role
        VARCHAR phone
        BOOL active
        ENUM approvalStatus
        BOOL emailVerified
    }
    students {
        BIGINT id PK
        BIGINT user_id FK
        VARCHAR rollNumber UK
        VARCHAR course
        INT semester
        VARCHAR section
        VARCHAR faceImagePath
    }
    teachers {
        BIGINT id PK
        BIGINT user_id FK
        VARCHAR employeeId UK
        VARCHAR department
    }
    subjects {
        BIGINT id PK
        VARCHAR code
        VARCHAR name
        VARCHAR section
        INT semester
        BIGINT teacher_id FK
    }
    attendance_sessions {
        BIGINT id PK
        BIGINT subject_id FK
        BIGINT teacher_id FK
        VARCHAR section
        DATETIME startTime
        DATETIME endTime
        BOOL active
    }
    attendance_records {
        BIGINT id PK
        BIGINT session_id FK
        BIGINT student_id FK
        DATETIME markedAt
        VARCHAR status
        VARCHAR verificationMethod
        DOUBLE latitude
        DOUBLE longitude
        DOUBLE accuracyMeters
        DOUBLE distanceMeters
    }
    attendance_verification_attempts {
        VARCHAR id PK
        BIGINT student_id FK
        BIGINT session_id FK
        DATETIME createdAt
        DATETIME expiresAt
        DATETIME completedAt
        ENUM status
        BIGINT version
    }
    face_embeddings {
        BIGINT id PK
        BIGINT student_id FK UK
        VARBINARY nonce
        VARBINARY ciphertext
        VARBINARY tag
        VARCHAR encryptionVersion
        TIMESTAMP createdAt
        TIMESTAMP updatedAt
    }
    assignments {
        BIGINT id PK
        BIGINT subject_id FK
        BIGINT teacher_id FK
        VARCHAR title
        TEXT description
        DATETIME dueDate
    }
    submissions {
        BIGINT id PK
        BIGINT assignment_id FK
        BIGINT student_id FK
        DATETIME submittedAt
        TEXT content
    }
    feedback_complaints {
        BIGINT id PK
        BIGINT raisedBy FK
        VARCHAR type
        VARCHAR subject
        TEXT message
        VARCHAR status
        TEXT resolution
        DATETIME createdAt
        DATETIME resolvedAt
    }

    users ||--o| students : "1:1"
    users ||--o| teachers : "1:1"
    teachers ||--o{ subjects : "teaches"
    teachers ||--o{ attendance_sessions : "creates"
    subjects ||--o{ attendance_sessions : "for"
    students ||--o{ attendance_records : "has"
    attendance_sessions ||--o{ attendance_records : "contains"
    students ||--o{ attendance_verification_attempts : "makes"
    attendance_sessions ||--o{ attendance_verification_attempts : "gates"
    students ||--o| face_embeddings : "1:1"
    subjects ||--o{ assignments : "has"
    teachers ||--o{ assignments : "creates"
    assignments ||--o{ submissions : "receives"
    students ||--o{ submissions : "submits"
    users ||--o{ feedback_complaints : "raises"
```

### Diagram 8 — Module Design

```mermaid
graph TD
    subgraph Android["Android Module"]
        AUTH_A[Auth Activities]
        DASH[DashboardActivity\nAll roles]
        FACE_A[FaceCaptureActivity\nCameraX]
        API_C[ApiClient\nRetrofit]
        LOC[LocationHelper\nGPS+Mock]
    end
    subgraph Backend["Backend Module"]
        SEC[Security\nJwtFilter+Config]
        AUTH_B[Auth\nController+Service]
        ATT[Attendance\nController+Service]
        FACE_B[Face\nController+Service]
        HOD_B[HOD\nController]
        TEACH[Teacher\nController]
        ENC[EncryptionUtil\nAES-256-GCM]
        GEO[GeofenceService]
    end
    subgraph FaceService["Face Service Module"]
        FLASK[Flask Routes]
        YUNET[YuNet Detection]
        SFACE[SFace Embedding]
        SPOOF[MiniFASNet\nAnti-Spoofing]
    end
    subgraph DB["Database"]
        USERS[(users)]
        ATTENDANCE[(attendance_*)]
        EMBED[(face_embeddings)]
    end

    AUTH_A --> API_C --> AUTH_B --> SEC
    DASH --> API_C --> ATT & TEACH & HOD_B
    FACE_A --> LOC
    FACE_A --> API_C --> FACE_B --> ENC --> EMBED
    FACE_B --> GEO
    FACE_B --> FLASK --> YUNET --> SPOOF --> SFACE
    AUTH_B --> USERS
    ATT --> ATTENDANCE
```

---

## 18. RECOMMENDED SCREENSHOTS FOR ACADEMIC REPORT

### Must-have (demonstrable right now)
1. Student login screen (`activity_main.xml`)
2. Student registration form (`activity_register.xml`)
3. OTP verification screen (`activity_otp_verification.xml`)
4. Student dashboard — showing profile + active session
5. FaceCaptureActivity — camera preview open
6. Attendance marked successfully — response with `attendanceId`
7. Attendance history list screen
8. Attendance summary with per-subject percentages
9. Teacher dashboard — session list
10. Teacher create session screen
11. Teacher session attendance view
12. HOD dashboard — students list
13. HOD pending teacher approval
14. Invalid login — 401 error message
15. Geofence rejection message on Android
16. Blurry image rejection message
17. Face not matched message
18. Flask health endpoint (`/health` → `{"status":"UP"}`)
19. Spring Boot startup log (no errors)
20. Test suite output: `106 passed` (Flask) + `BUILD SUCCESSFUL` (Android)

### Evidence unavailable without device testing
- Printed photo rejection (requires physical printed photo)
- Screen replay rejection (requires second phone)
- Genuine face liveness score visible in Flask logs
- Attendance record appearing in MySQL after marking

---

*End of PROJECT_CURRENT_STATE_AUDIT.md*
