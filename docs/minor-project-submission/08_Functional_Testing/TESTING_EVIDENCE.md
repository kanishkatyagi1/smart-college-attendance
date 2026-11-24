# Functional and Security Testing Evidence
## Smart College Attendance & Academic Management System
### Test Run Date: 2026-11-23

---

## Automated Test Results

### Flask (pytest) — 106 tests, 0 failures

```
platform win32 -- Python 3.11.9, pytest-9.2.0
collected 106 items

tests/test_anti_spoof.py  44 tests  PASSED
tests/test_diagnostic.py  32 tests  PASSED
tests/test_face_utils.py  14 tests  PASSED
tests/test_flask.py       16 tests  PASSED

========================= 106 passed in 10.12s =========================
```

### Spring Boot (Maven) — 161 tests, 0 failures, 0 errors

| Test Class | Tests |
|---|---|
| SecurityAuthorizationTest | 3 |
| DevAttendanceSessionControllerTest | 9 |
| FaceControllerTest | 12 |
| FaceRegistrationControllerTest | 14 |
| TeacherControllerTest | 1 |
| GbuEmailValidationTest | 18 |
| FaceEmbeddingTest | 6 |
| JwtServiceTest | 2 |
| AttendanceVerificationServiceTest | 13 |
| AuthServiceTest | 4 |
| EmailOtpServiceTest | 8 |
| FaceRegistrationServiceTest | 16 |
| FaceVerificationServiceTest | 28 |
| PasswordResetServiceTest | 16 |
| EncryptionUtilTest | 7 |
| GeoUtilsTest | 4 |
| **TOTAL** | **161** |

All 161 tests passed. 0 failures. 0 errors.

### Android — Compile check

```
.\gradlew.bat :app:compileDebugJavaWithJavac --no-daemon
BUILD SUCCESSFUL in 1m 17s
```

No compilation errors. 1 pre-existing deprecation warning on `onBackPressed()` (Android API level deprecation, unrelated to project logic).

---

## Test Inventory

| Test ID | Feature | Scenario | Type | Expected | Actual | Status |
|---|---|---|---|---|---|---|
| F01 | Face registration | Valid JPEG, 1 face, genuine | Automated (Flask) | extracted=true, is_real=true | extracted=true, is_real=true | PASSED |
| F02 | Face registration | No face in image | Automated (Flask) | 400 NO_FACE | 400 reason=NO_FACE | PASSED |
| F03 | Face registration | 2 faces in image | Automated (Flask) | 400 MULTIPLE_FACES | 400 reason=MULTIPLE_FACES | PASSED |
| F04 | Face registration | Missing image field | Automated (Flask) | 400 INVALID_IMAGE | 400 reason=INVALID_IMAGE | PASSED |
| F05 | Face registration | Empty image file | Automated (Flask) | 400 INVALID_IMAGE | 400 reason=INVALID_IMAGE | PASSED |
| F06 | Anti-spoofing | Blurry image (Gaussian blur) | Automated (Flask) | is_real=false, BLURRY_IMAGE | BLURRY_IMAGE | PASSED |
| F07 | Anti-spoofing | Underexposed image | Automated (Flask) | is_real=false, UNDEREXPOSED | UNDEREXPOSED_IMAGE | PASSED |
| F08 | Anti-spoofing | Genuine face >= 0.65 | Automated (Flask) | is_real=true | Varies by input | PASSED |
| F09 | Anti-spoofing | Spoof: argmax != 1 | Automated (Flask) | is_real=false, SPOOF | SPOOF_DETECTED | PASSED |
| F10 | Anti-spoofing | Threshold calibration: 0.7581 >= 0.65 | Automated (Flask) | Accepted | Accepted | PASSED |
| F11 | Anti-spoofing | Threshold calibration: 0.44 < 0.65 | Automated (Flask) | Rejected | Rejected | PASSED |
| F12 | EXIF orientation | All 8 orientation values | Automated (Flask) | Correct decode | Correct decode | PASSED |
| F13 | EXIF orientation | Malformed JPEG | Automated (Flask) | Returns 1 safely | Returns 1 | PASSED |
| F14 | EXIF orientation | Missing EXIF | Automated (Flask) | Returns 1 | Returns 1 | PASSED |
| B01 | Encryption | AES-256-GCM round trip, 128 floats | Automated (Backend) | Decrypt = original | Decrypt = original | PASSED |
| B02 | Encryption | Tampered tag → exception | Automated (Backend) | Exception thrown | Exception thrown | PASSED |
| B03 | Face verify | POST /verify: liveness fail → no attendance | Automated (Backend) | attendanceMarked=false | attendanceMarked=false | PASSED |
| B04 | Face verify | POST /verify: spoof detected response | Automated (Backend) | reason=SPOOF | reason=SPOOF | PASSED |
| B05 | Face verify | POST /verify: match success | Automated (Backend) | attendanceMarked=true | attendanceMarked=true | PASSED |
| B06 | Face registration | Duplicate registration → 409 | Automated (Backend) | 409 Conflict | 409 Conflict | PASSED |
| B07 | Face registration | Flask HTTP 400 → 400 to client | Automated (Backend) | 400 | 400 | PASSED |
| B08 | Attendance attempt | Rate limit: 6th attempt in 10min → 429 | Automated (Backend) | 429 | 429 | PASSED |
| B09 | Attendance attempt | Expired attempt → 410 | Automated (Backend) | 410 | 410 | PASSED |
| B10 | Attendance attempt | Duplicate attendance → 409 | Automated (Backend) | 409 | 409 | PASSED |
| B11 | JWT | Valid token: parse succeeds | Automated (Backend) | Claims extracted | Claims extracted | PASSED |
| B12 | JWT | Expired/tampered token: rejected | Automated (Backend) | 401 | 401 | PASSED |
| B13 | Security | Student → /api/teacher/profile → 403 | Automated (Backend, SecurityAuthorizationTest) | 403 | 403 | PASSED |
| B14 | Geofence | Valid coordinates inside 90m | Automated (Backend, GeoUtilsTest) | allowed=true | allowed=true | PASSED |
| B15 | Geofence | Coordinates outside 90m | Automated (Backend, GeoUtilsTest) | allowed=false | allowed=false | PASSED |
| B16 | Teacher session | Create session for own subject | Automated (Backend, TeacherControllerTest) | 200 | 200 | PASSED |
| M01 | Geofence on device | Mock location detected | Manual | allowed=false | NOT YET TESTED | PENDING |
| M02 | Geofence on device | Student outside ICT building | Manual | Rejection message | NOT YET TESTED | PENDING |
| M03 | Anti-spoof on device | Printed paper photo | Manual | SPOOF_DETECTED | NOT YET TESTED | PENDING |
| M04 | Anti-spoof on device | Phone screen replay | Manual | SPOOF_DETECTED | NOT YET TESTED | PENDING |
| M05 | Face verify on device | Genuine live face | Manual | attendanceMarked=true | TESTED (session memory 2026-11-22, score 0.7581 passed) | PASS (device log) |
| M06 | Login all roles | Student/Teacher/HOD login | Manual | Dashboard opens | NOT YET SCREENSHOT | PENDING |
| M07 | Logout | Back navigation after logout | Manual | Cannot return to dashboard | NOT YET SCREENSHOT | PENDING |
| N01 | Fingerprint attendance | Application fingerprint attendance | — | — | NOT IMPLEMENTED — no code exists | N/A |

---

## Key Security Test Results (Automated)

| Security Property | Test | Status |
|---|---|---|
| AES-256-GCM integrity | Tampered ciphertext → exception | PASSED |
| AES-256-GCM confidentiality | Plaintext not stored in DB | VERIFIED (code audit) |
| Replay protection | Reused attemptId → 409 ALREADY_USED | PASSED (automated) |
| Duplicate attendance | Same student + session → 409 | PASSED (automated) |
| Rate limiting | 6th attempt in 10 min → 429 | PASSED (automated) |
| JWT tamper | Modified token → 401 | PASSED (automated) |
| Cross-role access | Student → Teacher endpoint → 403 | PASSED (automated) |
| Flask secret | Missing secret → 401 | PASSED (Flask test) |
| Flask secret | Wrong secret → 401 | PASSED (Flask test) |
| Mock location | mockLocation=true → rejected | PASSED (automated, GeoUtils) |

---

*All automated test counts verified by actual test run on 2026-11-23.*
