# Active Liveness Runtime Debug Report

**Date:** 2026-11-24  
**Status:** Read-only investigation. No production code modified.

---

## 1. Actual Android Navigation Path

### 1.1 Code path from "Mark Attendance" button press

```
DashboardActivity.build()
  → btn("1. Mark Attendance (Face)", v -> loadActive())
  → loadActive()
      → GET api/student/active-session          (find open attendance session)
      → POST api/student/attendance/verification-attempt  (get attemptId)
      → start ActiveLivenessActivity {
            EXTRA_ATTEMPT_ID = attemptId
            EXTRA_SESSION_ID = sessionId
            EXTRA_REGISTER   = false
        }
```

**`registerFace()` path (same Activity is used):**
```
DashboardActivity.build()
  → btn("Register Face", v -> registerFace())
  → registerFace()
      → start ActiveLivenessActivity {
            EXTRA_REGISTER = true
        }
```

**Verdict:** `DashboardActivity` code is correct — it starts `ActiveLivenessActivity` for both paths. `FaceCaptureActivity` is never started directly from `DashboardActivity`.

### 1.2 `ActiveLivenessActivity` internal flow

```
onCreate()
  → requestCameraPermission()
      → (permission granted) → startLivenessSession()
          → POST api/face/active-liveness/sessions
              {purpose: "VERIFICATION", attemptId: "..."}
          → onResponse: livenessSessionId = session.sessionId
                        nextSequence = session.nextExpectedSequence
                        startCamera()
              → bindToLifecycle(Preview + ImageAnalysis)
              → ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
              → setAnalyzer → processFrame() per frame
                  → yuvToJpeg() → submitFrame()
                      → POST api/face/active-liveness/sessions/{id}/frames
                          ?sequence=N  frame=(JPEG bytes)
                  → handleSessionUpdate(response)
                      → state == "COMPLETED" → navigateToFaceCapture()
                          → start FaceCaptureActivity {
                                register = false
                                attemptId = <original attemptId>
                                sessionId = <original sessionId>
                            }
                          → finish()
```

---

## 2. The Bypass: Why Direct `/api/face/verify` Appears Without Active Liveness Requests

### 2.1 The APK installed on the test device is **stale**

The `app-debug.apk` in the build output has:
- `versionCode=1`, `versionName=1.0`
- Build timestamp: **2026-11-24 20:51** (IST)
- `ActiveLivenessActivity` is present in that APK's manifest

If the device shows `/api/face/verify` without any `/api/face/active-liveness/` requests, the device has an **older APK installed** — one built before `DashboardActivity` was changed to start `ActiveLivenessActivity`. Both APKs have the same `versionCode=1` and `versionName=1.0`, so the Android package manager will not prompt an upgrade.

**Verification step:** Run `adb shell dumpsys package com.college.attendance.mobile | findstr -i "version\|first"` on the device to see the actually installed APK timestamp and version.

### 2.2 Structural bypass paths in source code

A code review of the current source finds **no bypass** in `DashboardActivity` — both `loadActive()` and `registerFace()` now route through `ActiveLivenessActivity`. However, two conditions can cause `ActiveLivenessActivity` to be skipped silently:

#### Bypass path A: `startLivenessSession` returns HTTP 409

`ActiveLivenessController.start()` calls `attempts.requirePending(attemptId, student)` after creating the session. If the attempt is not `PENDING` (already used or session closed), Spring cancels the new liveness session and rethrows with HTTP 409. `ActiveLivenessActivity.startLivenessSession()` currently handles 409 like this:

```java
String msg = code == 409
    ? "Attendance already recorded for this session."
    : ...;
setStatus(msg);
alCancel.setText("GO BACK");
```

This shows an error and stops — it does **not** proceed to `FaceCaptureActivity`. Correct behaviour. But if the user presses the system back button from this error screen, they return to `DashboardActivity` and can tap "Mark Attendance" again, potentially with the same stale `attemptId`. The `startVerification` call would then return a fresh attempt, and the cycle restarts cleanly.

#### Bypass path B: Camera permission denied

If camera permission is denied, `ActiveLivenessActivity` shows an error and never calls `startLivenessSession()`. The user sees "Camera permission is required" and the GO BACK button — `FaceCaptureActivity` is never started. This is correct behaviour.

#### Bypass path C: `startLivenessSession` network failure

If the network call to start the session fails, `ActiveLivenessActivity` shows an error. `FaceCaptureActivity` is never reached. Correct.

**Conclusion:** There is no code path in the current source that skips `ActiveLivenessActivity` and jumps directly to `FaceCaptureActivity`. The bypass observed at runtime is caused by running a stale APK.

---

## 3. Spring Request Sequence (what the server receives when stale APK is running)

When the stale APK runs, the server receives:

```
POST  /api/student/attendance/verification-attempt    → 200 {attemptId, sessionId}
POST  /api/face/verify?attemptId=...                   → 200 {verified, ...}
```

There are **no** `/api/face/active-liveness/` requests in the log — consistent with the stale APK going directly to `FaceCaptureActivity`.

When the new APK runs, the expected sequence is:

```
POST  /api/student/attendance/verification-attempt    → 200 {attemptId, sessionId}
POST  /api/face/active-liveness/sessions              → 200 {sessionId, challenge, state=ACTIVE}
POST  /api/face/active-liveness/sessions/{id}/frames?sequence=0  → 200 {state=ACTIVE|PROCESSING}
POST  /api/face/active-liveness/sessions/{id}/frames?sequence=1  → ...
...
POST  /api/face/active-liveness/sessions/{id}/frames?sequence=N  → 200 {state=COMPLETED}
(→ Android navigates to FaceCaptureActivity)
POST  /api/face/verify?attemptId=...                   → 200 {verified, ...}
```

---

## 4. Flask Passive Rejection: Root Cause Analysis

### 4.1 What Flask does on each `/extract-embedding` call

```
app.py → extract_embedding_route()
  1. Authenticate: X-FACE-SERVICE-SECRET check
  2. Decode: decode_image_upright(raw_bytes)
  3. Detect: engine.detect_single_face_with_box(img)
  4. Assess: anti_spoof_engine.assess(img, face_info[:4])
      a. Quality gate (measured on FACE region, not full image):
         - blur:       Laplacian var on face region, normalised to 100×100 → threshold 50.0
         - brightness: mean of face region gray → threshold [15.0, 245.0]
         - face ratio: face_area / image_area → threshold 0.03
      b. If quality fails → return rejection_reason = "BLURRY_IMAGE" | "UNDEREXPOSED_IMAGE" | ...
      c. MiniFASNet ensemble (NHWC, only if quality passes):
         - V2 (scale 2.7): class probs [print, real, replay]
         - V1SE (scale 4.0): class probs [print, real, replay]
         - ensemble = V2_probs + V1SE_probs
         - real_face_score = ensemble[1] / 2.0
         - is_real = (argmax(ensemble)==1) AND (real_face_score >= 0.65)
  5. If not is_real → return {extracted=false, rejection_reason=...}
  6. If is_real → extract SFace embedding → return {extracted=true, embedding=[128 floats]}
```

### 4.2 Known rejection scenarios from prior sessions

| Scenario | Quality gate result | Ensemble score | Rejection |
|---|---|---|---|
| Uniform/smooth background, small face | blur pass, face ratio < 0.03 | (not reached) | `FACE_TOO_SMALL` |
| CameraX YUV frame at low quality | laplacian_var < 50 | (not reached) | `BLURRY_IMAGE` |
| Poor lighting | brightness_mean < 15 | (not reached) | `UNDEREXPOSED_IMAGE` |
| Genuine face, good quality | all pass | score ≈ 0.75–0.85 | `is_real=True` ✓ |
| Screen/photo replay | all pass | score ≈ 0.10–0.44 | `SPOOF_DETECTED` |

### 4.3 Most likely current rejection cause

The rejection comes from one of two sources, depending on the image source:

**If the stale APK is running (manual JPEG capture via `ImageCapture`):**
- `ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY` produces a full-resolution JPEG.
- Typical resolution: 1920×1080 or higher. Face occupies a small fraction → face ratio = (face area) / (1920×1080) can easily fall below 0.03.
- Or: the JPEG is sharp but the face region Laplacian normalises to a smooth value because the face is too small relative to image area.

**If the new APK is running (YUV→JPEG via `ActiveLivenessActivity.yuvToJpeg()`):**
- `ImageAnalysis` default resolution is typically 480×640.
- `YuvImage.compressToJpeg` at quality 75 produces a valid JPEG.
- Rotation: `imageProxy.getImageInfo().getRotationDegrees()` applies rotation — if the EXIF-encoded rotation and the in-memory rotation are both applied, the image may be double-rotated, causing Flask's `decode_image_upright` to produce a sideways face with 0 YuNet detections (HTTP 400, not a liveness rejection).
- If only the analysis frame rotationDegrees are non-zero and correctly applied, the face should be upright and YuNet should detect it.

### 4.4 The `_diag_*` fields are present but not logged

`anti_spoof_engine.assess()` returns `_diag_probabilities`, `_diag_m1_probabilities`, `_diag_m2_probabilities`, and `_diag_quality` in its dict. These keys are **never forwarded** by `app.py` to Spring — they are stripped by the `jsonify` response which only includes `extracted`, `is_real`, `liveness_score`, `rejection_reason`, and `embedding`. They are also **never written to any log file**. This means the per-model scores and quality metrics are invisible at runtime.

---

## 5. Diagnostic Logging Gaps and Recommendations

### 5.1 Flask — missing diagnostic output

**Gap:** `anti_spoof_engine.assess()` computes `laplacian_var`, `brightness_mean`, `face_ratio`, and per-model probabilities, but none of these are written to Flask's application log. When a rejection happens, the only visible information is the `rejection_reason` string in the response.

**Recommended addition in `app.py` `extract_embedding_route()`** (after calling `assess()`):

```python
# Safe diagnostic log — no image pixels, no embeddings, no JWT, no secret.
liveness = anti_spoof_engine.assess(img, face_info[:4])
_diag = liveness.get("_diag_quality", {})
app.logger.info(
    "[DIAG] assess: is_real=%s reason=%s liveness_score=%.4f "
    "laplacian_var=%.3f brightness=%.1f face_ratio=%.5f "
    "orientation=%d img_shape=%s",
    liveness["is_real"],
    liveness.get("rejection_reason"),
    liveness.get("liveness_score", 0.0),
    _diag.get("laplacian_var", -1),
    _diag.get("brightness_mean", -1),
    _diag.get("face_ratio", -1),
    orientation,
    str(img.shape),
)
if liveness["is_real"] is False and "_diag_m1_probabilities" in liveness:
    app.logger.info(
        "[DIAG] ensemble: V2=%s V1SE=%s ensemble_real=%.4f threshold=%.2f",
        [round(float(x), 4) for x in liveness["_diag_m1_probabilities"]],
        [round(float(x), 4) for x in liveness["_diag_m2_probabilities"]],
        liveness["liveness_score"],
        config.liveness_threshold,
    )
```

### 5.2 Android — missing active-liveness diagnostic tags

**Gap:** `ActiveLivenessActivity` logs state transitions via `Log.d(TAG, ...)` but does not log the HTTP status of each frame submission or the challenge being displayed. There is no Logcat tag that lets you distinguish a successful frame submission from a dropped one.

**Recommended Logcat tags to add** (never log JWT, secret, or pixel data):

```java
// In submitFrame() onResponse:
Log.d("ALiveness", "frame seq=" + seq
    + " state=" + s.state
    + " nextSeq=" + s.nextExpectedSequence
    + " challenge=" + s.challenge
    + " retryCount=" + s.retryCount);

// In processFrame() when dropping:
Log.v("ALiveness", "frame dropped: submitting=" + submitting.get()
    + " rateLimit=" + (now - lastSubmitMs < MIN_FRAME_INTERVAL_MS));

// In startLivenessSession() onResponse:
Log.i("ALiveness", "session started: id=" + s.sessionId
    + " challenge=" + s.challenge
    + " expiresAt=" + s.expiresAt
    + " nextSeq=" + s.nextExpectedSequence);
```

### 5.3 Spring — active-liveness frame submission already logs at INFO level

`HttpActiveLivenessEvaluator` logs:
```
Active liveness: session=<id> seq=<n> decision=<d> reason=<r>
```
This is sufficient for frame-level tracing. No changes needed here.

---

## 6. Files Requiring Correction

### 6.1 Immediate: reinstall the APK

**Root cause of bypass:** The device is running a stale APK (pre-active-liveness). The fix is a reinstall, not a code change.

```
# From android-app/
.\gradlew.bat installDebug
# or:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Confirm after install:
```
adb shell dumpsys package com.college.attendance.mobile | grep -i "version\|activity"
# Must show: ActiveLivenessActivity in the activity list
```

### 6.2 Flask: add diagnostic logging (recommended, non-breaking)

**File:** `face-service/app.py`  
**Change:** Add `INFO`-level log lines after `anti_spoof_engine.assess()` (see §5.1).  
**Risk:** Zero — existing logic unchanged, only structured log output added.  
**Benefit:** Makes rejection reason + quality metrics + per-model scores visible in Flask's stdout without touching thresholds, models, or preprocessing.

### 6.3 Android: add per-frame Logcat diagnostics (recommended, non-breaking)

**File:** `android-app/.../ui/ActiveLivenessActivity.java`  
**Change:** Add `Log.d("ALiveness", ...)` calls in `submitFrame()` and `processFrame()` (see §5.2).  
**Risk:** Zero — Logcat output only, no logic change.  
**Benefit:** Confirms frames are being submitted and what decision the server returns for each.

### 6.4 Potential double-rotation bug in `ActiveLivenessActivity.yuvToJpeg()`

**File:** `android-app/.../ui/ActiveLivenessActivity.java`  
**Location:** `yuvToJpeg()` → calls `rotateJpeg(jpeg, rotationDegrees)` after `YuvImage.compressToJpeg`.

**Risk:** `YuvImage.compressToJpeg` does **not** embed EXIF metadata, so the resulting JPEG has no EXIF orientation tag. Flask's `decode_image_upright` reads EXIF and rotates accordingly; it will return orientation=1 (no rotation) for a tag-less JPEG. The `rotateJpeg()` call in `yuvToJpeg()` correctly applies the rotation in-pixel before sending.

However, if `rotationDegrees` from `imageProxy.getImageInfo().getRotationDegrees()` returns a non-zero value and the rotation is applied correctly, the face will be upright when Flask receives it. This path is correct **provided** the rotation degrees are reported accurately by CameraX for the front camera at the current display orientation.

**Verification:** Add a Logcat line `Log.d("ALiveness", "frame rotDeg=" + rotationDegrees + " w=" + imageProxy.getWidth() + " h=" + imageProxy.getHeight())` to confirm the degrees are non-zero and consistent on the target device. A common failure mode on some devices is `rotationDegrees=0` when the sensor is sideways, leaving an un-rotated landscape face that YuNet cannot detect.

### 6.5 Spring: `versionCode=1` prevents automatic reinstall detection

The `versionCode` and `versionName` have not been changed between the old and new APKs, so Android will silently keep the old APK if the user does not explicitly reinstall. For a development workflow, increment `versionCode` in `build.gradle` before each test build.

---

## 7. Summary

| Finding | Root cause | Fix |
|---|---|---|
| `/api/face/verify` without active-liveness requests | Stale APK on device (pre-integration build) | Reinstall APK: `gradlew installDebug` |
| Passive rejection visible at runtime | No diagnostic logging in Flask | Add `INFO` log after `assess()` in `app.py` |
| Rejection reason unknown (BLURRY vs SPOOF vs ratio) | `_diag_*` fields not logged | Same Flask logging fix |
| Cannot trace per-frame server decision in Logcat | No per-frame log in `ActiveLivenessActivity` | Add `Log.d("ALiveness", ...)` in `submitFrame()` |
| Potential double-rotation or zero-rotation on some devices | `rotationDegrees` not verified on target device | Add rotation Logcat; verify with physical device |
| APK version not bumped | `versionCode=1` unchanged | Increment `versionCode` in `build.gradle` for each build |

No production logic, thresholds, models, or preprocessing should be changed. The fixes are: APK reinstall, diagnostic logging, and version code management.
