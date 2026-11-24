# Android Active Liveness Integration Plan

**Status:** Plan only — read-only audit. No code modified.  
**Scope:** Android integration with the Spring-owned active-liveness protocol.  
**Out of scope:** Changes to Flask, Spring backend logic, model weights, thresholds, geofence, or existing face recognition flow.

---

## 1. What Already Exists

### 1.1 Android (CameraX)

`FaceCaptureActivity.java` owns the current face flow:

| Concern | Current state |
|---|---|
| Camera use-cases bound | `Preview` + `ImageCapture` only. No `ImageAnalysis`. |
| Capture trigger | Manual: user taps "CAPTURE PHOTO", then "VERIFY / REGISTER PHOTO". |
| Upload | Full-resolution JPEG written to `getCacheDir()/face.jpg`, then POSTed as `multipart/form-data`. |
| Front camera | `CameraSelector.DEFAULT_FRONT_CAMERA`. |
| Rotation | `getDisplay().getRotation()` applied to both `Preview` and `ImageCapture` at start; refreshed in `onResume`. |
| Session fields in intent | `attemptId` (String), `sessionId` (Long), `register` (boolean). |
| Fresh-attempt logic | `requestFreshAttempt()` calls `POST api/student/attendance/verification-attempt` with `sessionId` after a face rejection. |
| Activity teardown | `cameraProvider.unbindAll()` in `onDestroy`. |

### 1.2 Spring Active-Liveness API (actual endpoints — read from controller)

Base path: `POST|GET|DELETE /api/face/active-liveness/sessions/...`  
All endpoints require JWT `Authorization: Bearer <token>`.

| Method | Path | Body / params | Response |
|---|---|---|---|
| `POST` | `/api/face/active-liveness/sessions` | JSON `ActiveLivenessStartRequest` | `ActiveLivenessSessionResponse` |
| `POST` | `/api/face/active-liveness/sessions/{sessionId}/frames` | multipart: `frame` (JPEG) + query `sequence` (long) | `ActiveLivenessSessionResponse` |
| `GET` | `/api/face/active-liveness/sessions/{sessionId}` | — | `ActiveLivenessSessionResponse` |
| `DELETE` | `/api/face/active-liveness/sessions/{sessionId}` | — | `ActiveLivenessSessionResponse` |

### 1.3 DTOs (exact field names, read from Java records)

**`ActiveLivenessStartRequest`** (JSON body, POST to start)
```json
{
  "purpose": "REGISTRATION" | "VERIFICATION",
  "attemptId": "<string> | null"
}
```
`attemptId` is required when `purpose == VERIFICATION`; must be absent (null) for `REGISTRATION`.

**`ActiveLivenessSessionResponse`** (all responses)
```json
{
  "sessionId":             "<uuid string>",
  "purpose":               "REGISTRATION" | "VERIFICATION",
  "state":                 "ACTIVE" | "PROCESSING" | "COMPLETED" | "FAILED" | "CANCELLED" | "EXPIRED",
  "challenge":             ["BLINK"] | ["TURN_LEFT"] | ["TURN_RIGHT"] | ["BLINK","TURN_LEFT"] | ...,
  "expiresAt":             "<ISO-8601 Instant>",
  "retryCount":            0,
  "nextExpectedSequence":  0,
  "reason":                "<string> | null"
}
```

**`ActiveLivenessAction` enum values** (from `ActiveLivenessAction.java`)
```
BLINK, TURN_LEFT, TURN_RIGHT
```

**`ActiveLivenessState` terminal states**
- `COMPLETED` — liveness passed. Use to proceed to face capture.
- `FAILED` — permanent failure. Surface `reason` to user.
- `CANCELLED` — Android or server cancelled.
- `EXPIRED` — session timeout exceeded (default 6 s per property `active.liveness.session-timeout-seconds`).

### 1.4 Server constraints (from `application.properties` and `ActiveLivenessSessionService`)

| Property | Default | Meaning |
|---|---|---|
| `active.liveness.session-timeout-seconds` | 6 s | Session expires 6 s after `start`. Clock is server-side. |
| `active.liveness.max-frame-bytes` | 1 048 576 (1 MiB) | Max JPEG size per frame submission. |
| `active.liveness.max-frames` | 30 | Submission limit before FAILED/FRAME_LIMIT. |
| `active.liveness.max-retries` | 1 | Spring allows 1 retry (new challenge issued). |
| `active.liveness.min-frame-interval-ms` | 100 ms | Min gap between frames; faster returns 429. |
| Content-Type check | `image/jpeg` only | Spring's `validateAndReadFrame` rejects anything else. |

### 1.5 Existing Android models

`VerificationAttempt` — `attemptId` (String), `sessionId` (Long), `expiresAt` (String), `status` (String).  
`FaceResponse` — `verified`, `registered`, `attendanceMarked`, `message`, `error`, `similarity`, `reason`.

### 1.6 Retrofit / OkHttp

`ApiClient` builds a singleton `Retrofit` instance with `GsonConverterFactory`, JWT interceptor from `SharedPreferences("auth")`, base URL `http://10.0.2.2:8080/`. **No Flask URL is present and none should be added.**

### 1.7 Build config

```
minSdk 24  (Android 7.0)
compileSdk 35
CameraX 1.4.2  (camera-core, camera-camera2, camera-lifecycle, camera-view)
Retrofit 2.11.0 + Gson converter
OkHttp 4.12.0 logging interceptor
```
`ImageAnalysis` is part of `camera-core:1.4.2` — **no new Gradle dependency required**.

---

## 2. Files to Create or Modify

### 2.1 New Java files

| File | Purpose |
|---|---|
| `model/ActiveLivenessSession.java` | POJO mirroring `ActiveLivenessSessionResponse` for Gson. |
| `model/ActiveLivenessStartRequest.java` | POJO for the JSON start body. |
| `ui/ActiveLivenessActivity.java` | New activity: runs the full liveness challenge loop. |
| `res/layout/activity_active_liveness.xml` | Layout: `PreviewView` + `TextView` instruction + `ProgressBar` + cancel button. |

### 2.2 Modified files

| File | Change |
|---|---|
| `api/ApiService.java` | Add four Retrofit methods for the active-liveness endpoints. |
| `ui/DashboardActivity.java` | For attendance: start `ActiveLivenessActivity` before `FaceCaptureActivity` when active liveness is enabled. For registration: same. |
| `ui/FaceCaptureActivity.java` | Receive completed `livenessSessionId` in intent; pass it in the face-verify or face-register call if the backend is updated to accept it (Phase 2 concern — see §8). For now FaceCaptureActivity is unchanged. |
| `AndroidManifest.xml` | Declare `ActiveLivenessActivity`. |

---

## 3. Android Models to Add

```java
// model/ActiveLivenessSession.java
public class ActiveLivenessSession {
    public String sessionId;
    public String purpose;        // "REGISTRATION" | "VERIFICATION"
    public String state;          // "ACTIVE" | "PROCESSING" | "COMPLETED" | ...
    public List<String> challenge; // ["BLINK", "TURN_LEFT", ...]
    public String expiresAt;
    public int retryCount;
    public long nextExpectedSequence;
    public String reason;
}

// model/ActiveLivenessStartRequest.java
public class ActiveLivenessStartRequest {
    public String purpose;    // "REGISTRATION" | "VERIFICATION"
    public String attemptId;  // null for REGISTRATION, required for VERIFICATION
    public ActiveLivenessStartRequest(String purpose, String attemptId) { ... }
}
```

---

## 4. ApiService Additions

Add these four methods to `ApiService.java`. The base path matches `ActiveLivenessController`:

```java
// Start a liveness session
@POST("api/face/active-liveness/sessions")
Call<ActiveLivenessSession> startLivenessSession(
    @Body ActiveLivenessStartRequest request
);

// Submit one JPEG frame
@Multipart
@POST("api/face/active-liveness/sessions/{sessionId}/frames")
Call<ActiveLivenessSession> submitLivenessFrame(
    @Path("sessionId") String sessionId,
    @Query("sequence") long sequence,
    @Part("frame") MultipartBody.Part frame
);

// Poll session status
@GET("api/face/active-liveness/sessions/{sessionId}")
Call<ActiveLivenessSession> getLivenessSession(
    @Path("sessionId") String sessionId
);

// Cancel session
@DELETE("api/face/active-liveness/sessions/{sessionId}")
Call<ActiveLivenessSession> cancelLivenessSession(
    @Path("sessionId") String sessionId
);
```

---

## 5. CameraX Architecture for `ActiveLivenessActivity`

### 5.1 Three use-cases bound simultaneously

```
ProcessCameraProvider.bindToLifecycle(
    this,
    CameraSelector.DEFAULT_FRONT_CAMERA,
    preview,        // PreviewView display (unchanged from FaceCaptureActivity)
    imageAnalysis   // NEW: continuous frame delivery
)
// ImageCapture is NOT bound here — liveness uses ImageAnalysis only.
// ImageCapture stays in FaceCaptureActivity for the final registration/verify shot.
```

### 5.2 `ImageAnalysis` configuration

```java
ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
    .setTargetRotation(rotation)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
    .build();

imageAnalysis.setAnalyzer(executor, imageProxy -> {
    // --- CRITICAL: always close imageProxy, even on early return ---
    try {
        processFrame(imageProxy);
    } finally {
        imageProxy.close();
    }
});
```

`STRATEGY_KEEP_ONLY_LATEST` drops frames automatically when the analyzer is busy — this prevents unbounded backpressure and matches the server's 100 ms minimum frame interval.

### 5.3 YUV_420_888 → JPEG conversion

CameraX `ImageAnalysis` delivers `YUV_420_888` frames. The server accepts only `image/jpeg`. Conversion steps:

```java
private byte[] yuvToJpeg(ImageProxy imageProxy) {
    // 1. Convert YUV_420_888 → NV21 byte array
    byte[] nv21 = yuv420ToNv21(imageProxy);   // see §5.4

    // 2. Encode NV21 → JPEG via YuvImage
    YuvImage yuvImage = new YuvImage(
        nv21,
        ImageFormat.NV21,
        imageProxy.getWidth(),
        imageProxy.getHeight(),
        null
    );
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    // quality 85 keeps most frames under 200–300 KB (well inside 1 MiB limit)
    yuvImage.compressToJpeg(
        new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()),
        85,
        baos
    );
    return baos.toByteArray();
}
```

### 5.4 YUV_420_888 → NV21 conversion

`ImageProxy` with `YUV_420_888` has three planes: Y (plane 0), U (plane 1), V (plane 2). NV21 layout is Y-plane followed by interleaved V-U pairs.

```java
private byte[] yuv420ToNv21(ImageProxy imageProxy) {
    ImageProxy.PlaneProxy yPlane = imageProxy.getPlanes()[0];
    ImageProxy.PlaneProxy uPlane = imageProxy.getPlanes()[1];
    ImageProxy.PlaneProxy vPlane = imageProxy.getPlanes()[2];

    int width  = imageProxy.getWidth();
    int height = imageProxy.getHeight();
    int ySize  = width * height;
    byte[] nv21 = new byte[ySize + ySize / 2];

    // Copy Y plane (may have row stride padding — copy row by row)
    ByteBuffer yBuf = yPlane.getBuffer();
    int yRowStride = yPlane.getRowStride();
    for (int row = 0; row < height; row++) {
        yBuf.position(row * yRowStride);
        yBuf.get(nv21, row * width, width);
    }

    // Interleave V and U into NV21 (V first, then U)
    ByteBuffer uBuf = uPlane.getBuffer();
    ByteBuffer vBuf = vPlane.getBuffer();
    int uvPixelStride = uPlane.getPixelStride();
    int uvRowStride   = uPlane.getRowStride();
    int uvWidth  = width  / 2;
    int uvHeight = height / 2;
    for (int row = 0; row < uvHeight; row++) {
        for (int col = 0; col < uvWidth; col++) {
            int uvPos = row * uvRowStride + col * uvPixelStride;
            nv21[ySize + row * uvWidth * 2 + col * 2]     = vBuf.get(uvPos); // V
            nv21[ySize + row * uvWidth * 2 + col * 2 + 1] = uBuf.get(uvPos); // U
        }
    }
    return nv21;
}
```

**Alternative (simpler, but requires minSdk 26):** `ImageProxy` on API 26+ exposes `Bitmap` conversion via `ImageProxy.toBitmap()`. Since `minSdk 24`, use the manual NV21 path above, or use the CameraX `BitmapUtils` utility that ships with `camera-core:1.4.2`.

### 5.5 Front-camera rotation and mirroring

| Property | Value | Reason |
|---|---|---|
| Sensor rotation | Typically 270° front | Physical sensor mount. |
| CameraX `setTargetRotation` | `display.getRotation()` | Tells CameraX the current display orientation. |
| `PreviewView` | Auto-mirrors in `FRONT_CAMERA` | PreviewView mirrors for front camera; this is display-only. |
| `ImageAnalysis` frames | NOT mirrored | Raw sensor data, not display data. |
| Flask/MediaPipe | Expects upright, un-mirrored BGR | The existing `decode_image_upright` in `app.py` handles EXIF rotation. |

**Rotation strategy:** Set `setTargetRotation(display.getRotation())` on `ImageAnalysis` (same as current `ImageCapture` in `FaceCaptureActivity`). CameraX will apply EXIF metadata to the JPEG if `OUTPUT_IMAGE_FORMAT_JPEG` is used, or embed rotation in the buffer metadata for YUV. When converting YUV → JPEG manually with `YuvImage.compressToJpeg`, apply the rotation from `imageProxy.getImageInfo().getRotationDegrees()` by writing the EXIF Orientation tag into the JPEG bytes, OR by rotating the bitmap before compression. The simplest reliable approach:

```java
int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
// After converting to Bitmap (or after JPEG bytes):
Matrix matrix = new Matrix();
matrix.postRotate(rotationDegrees);
// For YuvImage path: rotate after Bitmap.createBitmap(...)
// then re-compress. The existing decode_image_upright in Flask handles
// EXIF; alternatively rotate the Bitmap before the final JPEG encode.
```

Do not mirror the JPEG before sending. The Flask `decode_image_upright` does not un-mirror; the face embedding is taken from the upright image. Mirroring is display-only in `PreviewView`.

### 5.6 Frame size budget

Front camera at default analysis resolution is typically 480×640 or 640×480. At JPEG quality 85, that is ~100–200 KB per frame, well below the 1 MiB server limit (`active.liveness.max-frame-bytes=1048576`). If the camera delivers higher resolution, set an explicit target resolution on `ImageAnalysis`:

```java
new ImageAnalysis.Builder()
    .setTargetResolution(new Size(640, 480))
    ...
```

### 5.7 Concurrency guard

The analyzer callback runs on a background executor. Only one frame should be in-flight to Spring at a time (the server enforces 100 ms minimum anyway). Use an `AtomicBoolean submitting`:

```java
private final AtomicBoolean submitting = new AtomicBoolean(false);

private void processFrame(ImageProxy imageProxy) {
    if (!submitting.compareAndSet(false, true)) {
        return; // previous frame still in-flight; drop this one
    }
    byte[] jpeg = yuvToJpeg(imageProxy);
    long seq = nextSequence.getAndIncrement();
    submitFrame(jpeg, seq, () -> submitting.set(false)); // callback on completion
}
```

---

## 6. Session Lifecycle in `ActiveLivenessActivity`

### 6.1 Full state machine

```
onCreate
  │
  ├─ register=true  → startLivenessSession(purpose=REGISTRATION, attemptId=null)
  └─ register=false → startLivenessSession(purpose=VERIFICATION, attemptId=<from intent>)
         │
         ▼
   session.state == ACTIVE
   challenge = ["BLINK"] or ["TURN_LEFT","TURN_RIGHT"] etc.
   show instruction label (§6.3)
   start ImageAnalysis → submit frames
         │
         ├── response.state == ACTIVE      → keep sending frames
         ├── response.state == PROCESSING  → pause: previous frame evaluating
         ├── response.state == COMPLETED   → LIVENESS PASSED → proceed to FaceCaptureActivity
         ├── response.state == FAILED      → show reason → offer retry or finish
         ├── response.state == EXPIRED     → session timed out → restart or finish
         └── response.state == CANCELLED   → finish
```

### 6.2 Server-side timeout is 6 seconds

The server sets `expiresAt = now + 6s` on `start()`. The Android client must send frames quickly (the analyzer at 30 fps can submit 1 frame / 100 ms minimum). The session will expire server-side regardless of Android activity lifecycle — there is no Android-side timer to manage.

### 6.3 Challenge → user instruction mapping

| `ActiveLivenessAction` | Instruction string |
|---|---|
| `BLINK` | "Blink your eyes" |
| `TURN_LEFT` | "Turn your head left" |
| `TURN_RIGHT` | "Turn your head right" |

Multi-challenge display: show the whole list, highlight the current one as frames progress. The server does not expose which challenge index is active — the Android UI infers it from progress: once calibration completes (Flask returns PENDING but no RETRYABLE_FAILURE for ≥12 frames), begin highlighting challenges in order.

**Simpler approach for Phase 1:** Show all challenges at once and let the server decide when each is satisfied. The session `state` going to `COMPLETED` is the only confirmation needed.

### 6.4 Cancel on lifecycle events

| Event | Action |
|---|---|
| `onPause()` | Stop `ImageAnalysis` (unbind or pause analyzer) |
| `onStop()` | Call `DELETE /api/face/active-liveness/sessions/{sessionId}` to avoid holding a slot |
| `onDestroy()` | `cameraProvider.unbindAll()` |
| Back button | Cancel session, then `finish()` |

This is important for the server's bounded session limit (`max-sessions=1000`).

### 6.5 Activity recreation (rotation)

`ActiveLivenessActivity` should declare `android:configChanges="orientation|screenSize"` in `AndroidManifest.xml` to prevent recreation during the 6-second window. If the activity is recreated anyway, the session ID must be saved in `onSaveInstanceState` and the session polled (GET) on `onCreate` to determine if it is still `ACTIVE`. If `EXPIRED`, start a new session.

### 6.6 Retry policy

The server allows 1 retry (`max-retries=1`). When `state == ACTIVE` and `retryCount == 1`, the server has already issued a new challenge in `session.challenge`. Android must update the displayed instruction to the new challenge and continue sending frames with the incremented `nextExpectedSequence`.

---

## 7. Registration and Verification Integration

### 7.1 Verification flow (current → new)

**Current:**
```
DashboardActivity → loadActive()
  → POST api/student/attendance/verification-attempt  (get attemptId)
  → start FaceCaptureActivity (attemptId, sessionId)
  → user taps CAPTURE → VERIFY
  → POST api/face/verify?attemptId=...
```

**New:**
```
DashboardActivity → loadActive()
  → POST api/student/attendance/verification-attempt  (get attemptId)
  → start ActiveLivenessActivity (register=false, attemptId=<id>, sessionId=<id>)
      → POST api/face/active-liveness/sessions (purpose=VERIFICATION, attemptId=<id>)
      → submit frames until state==COMPLETED
      → on COMPLETED: start FaceCaptureActivity (attemptId=<id>, sessionId=<id>)
        → user taps CAPTURE PHOTO → VERIFY PHOTO (existing flow, unchanged)
  → on FAILED/EXPIRED: show error, offer retry from dashboard
```

The `ActiveLivenessActivity` is inserted between `DashboardActivity` and `FaceCaptureActivity`. `FaceCaptureActivity` and the existing `POST api/face/verify` call are **unchanged**.

### 7.2 Registration flow (current → new)

**Current:**
```
DashboardActivity → registerFace()
  → start FaceCaptureActivity (register=true)
  → POST api/face/register
```

**New:**
```
DashboardActivity → registerFace()
  → start ActiveLivenessActivity (register=true)
      → POST api/face/active-liveness/sessions (purpose=REGISTRATION, attemptId=null)
      → submit frames until state==COMPLETED
      → on COMPLETED: start FaceCaptureActivity (register=true)
        → POST api/face/register (unchanged)
  → on FAILED/EXPIRED: show error
```

### 7.3 `DashboardActivity` changes (minimal)

Replace the two `startActivity` calls:
- `loadActive()`: after getting `VerificationAttempt`, start `ActiveLivenessActivity` instead of `FaceCaptureActivity`, passing `attemptId`, `sessionId`, and `register=false`.
- `registerFace()`: start `ActiveLivenessActivity` with `register=true`.

`ActiveLivenessActivity` starts `FaceCaptureActivity` on `COMPLETED` and passes the same intent extras through.

### 7.4 `FaceCaptureActivity` changes

None required for Phase 1. The activity receives the same `attemptId`, `sessionId`, and `register` extras as before, and calls the same endpoints. The active-liveness gate is entirely in `ActiveLivenessActivity`.

---

## 8. Security Constraints

| Rule | How it is met |
|---|---|
| Android communicates only with Spring | `ApiClient` has base URL `http://10.0.2.2:8080/`. No Flask URL is ever added. |
| No `FACE_SERVICE_SECRET` in Android | The secret lives in Spring's `application.properties` / env var only. |
| Client never asserts liveness success | `ActiveLivenessActivity` reads `state == COMPLETED` from the server response; it never sets a local `livenessOk = true` flag. |
| Server controls challenge sequence | `challenge` list comes from `ActiveLivenessSessionResponse.challenge`. Android renders it; it never generates or reorders it. |
| Server controls deadlines | `expiresAt` is server-set. Android does not need a client-side countdown. |
| Session belongs to authenticated student | JWT is added by `ApiClient` interceptor on every request. |
| `COMPLETED` ≠ attendance marked | The controller has no attendance-marking operation. `FaceCaptureActivity` → `POST api/face/verify` is still required. |

---

## 9. Layout (`activity_active_liveness.xml`) Sketch

```xml
<LinearLayout orientation="vertical">
    <TextView  id="@+id/alTitle"       text="Liveness Check" textSize="22sp" />
    <TextView  id="@+id/alInstruction" text=""               textSize="16sp" />
    <!-- One line per challenge, highlighted in sequence -->
    <TextView  id="@+id/alChallenge1"  text=""               textSize="15sp" />
    <TextView  id="@+id/alChallenge2"  text=""               textSize="15sp" />

    <PreviewView id="@+id/alCameraPreview"
        layout_weight="1"
        scaleType="fitCenter"
        implementationMode="compatible" />

    <ProgressBar id="@+id/alProgress" style="?android:progressBarStyleHorizontal"
        max="100" progress="0" />

    <Button id="@+id/alCancel" text="CANCEL" />
</LinearLayout>
```

---

## 10. Test Plan

### 10.1 Unit tests (JVM, no device)

| Test | What to verify |
|---|---|
| `ActiveLivenessSessionTest` (extend existing Spring tests) | Challenge mapping to instruction strings. |
| `YuvToJpegTest` | NV21 conversion produces valid JPEG bytes; output is under 1 MiB for 640×480. |
| `ActiveLivenessActivityViewModelTest` | State machine: start → ACTIVE → COMPLETED triggers transition to FaceCaptureActivity. FAILED shows error. EXPIRED triggers new session. |

### 10.2 Instrumented / Espresso tests

| Test | What to verify |
|---|---|
| `ActiveLivenessActivityTest` | Mock `ApiService` returns ACTIVE → COMPLETED; confirms `FaceCaptureActivity` is started. |
| | FAILED response shows reason text. |
| | Cancel button calls DELETE and finishes. |
| | Activity rotation: session ID survives config change via `savedInstanceState`. |

### 10.3 Manual integration test (device + running server)

1. Run Spring Boot (`java -jar`, real MySQL, `FACE_SERVICE_SECRET` set, Flask running on port 5001).
2. Install APK on physical device or emulator.
3. Student login → Mark Attendance.
4. Observe liveness session starts, frames flow, challenge instruction displayed.
5. Complete BLINK challenge → confirm state reaches `COMPLETED`.
6. Confirm `FaceCaptureActivity` opens; submit photo; confirm attendance marked.
7. Test session expiry: delay frame submission past 6 s; verify `EXPIRED` shown.
8. Test network loss mid-session; verify retry or error message.
9. Test activity rotation during liveness; verify session resumes.

---

## 11. Risks and Dependencies

| Risk | Severity | Mitigation |
|---|---|---|
| 6-second timeout is very short for the full calibration + challenge cycle (MediaPipe needs ~12 calibration frames at ~100 ms each = 1.2 s, then challenge frames) | High | Increase `ACTIVE_LIVENESS_SESSION_TIMEOUT_SECONDS` to 30 in `application.properties` for the first integration test. |
| Front-camera JPEG mirroring confuses head-turn direction | Medium | Test TURN_LEFT vs. TURN_RIGHT with a physical device. Flask's yaw is computed from the un-mirrored raw frame; the instruction label must match the camera's physical (not mirror) orientation. |
| `STRATEGY_KEEP_ONLY_LATEST` may drop frames at high camera fps | Low | Server's 100 ms minimum enforces rate naturally; dropping frames is correct behaviour. |
| Activity recreation during liveness invalidates camera binding | Medium | Declare `configChanges="orientation|screenSize"` to suppress recreation. |
| `YuvImage.compressToJpeg` output may vary by device | Low | Test output size on target devices; adjust quality parameter if needed. |
| `minSdk 24` rules out `ImageProxy.toBitmap()` shortcut (API 26+) | Low | Manual NV21 conversion covers all supported devices. |
| No Spring test covers the HTTP contract between `ActiveLivenessController` and Android | Medium | Add `ActiveLivenessControllerIntegrationTest` using `MockMvc` for the four endpoints. |
| `ApiClient` is a singleton — adding new `ApiService` methods at runtime is fine, but a token refresh race during a 30-frame session could 401 mid-session | Low | JWT expiry is `app.jwt.expiration-ms=86400000` (24 h), far longer than any liveness session. |

---

## 12. Summary of Exact Changes Required

| File | Type | Change |
|---|---|---|
| `model/ActiveLivenessSession.java` | **New** | POJO for `ActiveLivenessSessionResponse` |
| `model/ActiveLivenessStartRequest.java` | **New** | POJO for start request body |
| `api/ApiService.java` | **Modify** | Add 4 methods: `startLivenessSession`, `submitLivenessFrame`, `getLivenessSession`, `cancelLivenessSession` |
| `ui/ActiveLivenessActivity.java` | **New** | Frame loop, challenge UI, session lifecycle, YUV→JPEG, state machine |
| `res/layout/activity_active_liveness.xml` | **New** | PreviewView + instruction labels + progress + cancel button |
| `AndroidManifest.xml` | **Modify** | Declare `ActiveLivenessActivity` with `configChanges="orientation|screenSize"` |
| `ui/DashboardActivity.java` | **Modify** | 2 lines: start `ActiveLivenessActivity` instead of `FaceCaptureActivity` |
| `application.properties` | **Modify** | Raise `active.liveness.session-timeout-seconds` to 30 for first integration |
| `ui/FaceCaptureActivity.java` | **No change** | Existing capture-and-submit flow preserved |
| `api/ApiClient.java` | **No change** | No Flask URL; JWT interceptor unchanged |
