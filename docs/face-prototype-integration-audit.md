# Face Prototype Integration Audit

## Scope and evidence

This is a design audit, not an implementation. It traces the current checkout on
`face-subsystem-rebuild` and compares it with the supplied prototype inventory.
No production code, model, threshold, image, embedding, database record, or
secret was changed.

The prototype source tree is not present in this checkout. Prototype statements
therefore reflect the supplied module inventory; current-project conclusions are
traced to the exact files and methods below.

## Current implementation inventory

### Actual call path

```text
CameraX still JPEG
  -> authenticated Android Retrofit request
  -> Spring /api/face/register or /api/face/verify
  -> Flask POST /extract-embedding with service secret
  -> decode/orient -> exactly-one YuNet -> quality + MiniFASNet
  -> only if live: SFace aligned embedding
  -> Spring encrypted registration persistence or cosine comparison
  -> only after every verification gate: attendance record
```

| Area | Implemented behavior | Source evidence |
|---|---|---|
| Android capture | CameraX `Preview` and `ImageCapture` create one cached `face.jpg`; the user uploads it after capture. There is no `ImageAnalysis`, `ImageProxy`, streaming-frame protocol, MediaPipe client, challenge UI, or active-liveness frame loop. | `android-app/app/src/main/java/com/college/attendance/mobile/ui/FaceCaptureActivity.java`: `startCamera`, `capturePhoto`, `sendWithLocation` |
| Public API boundary | Android calls `api/face/register` and `api/face/verify` only. It has no Flask URL or service secret. | `android-app/app/src/main/java/com/college/attendance/mobile/api/ApiService.java`: `registerFace`, `verifyFace` |
| Registration | Student-only Spring registration sends the image to Flask, validates 128 values, AES-256-GCM encrypts them, and persists only nonce/ciphertext/tag. | `backend/src/main/java/com/college/attendance/controller/FaceRegistrationController.java`: `register`; `backend/src/main/java/com/college/attendance/service/FaceRegistrationService.java`: `registerFace`, `callFlaskExtract`, `validateEmbedding`, `encryptEmbedding` |
| Verification | Spring requires the authenticated student's pending attempt, applies geofence validation, invokes face verification, consumes failed attempts, and makes an attendance record only after live and matched results. | `backend/src/main/java/com/college/attendance/controller/FaceController.java`: `verify`; `backend/src/main/java/com/college/attendance/service/FaceVerificationService.java`: `verifyStudentFace`; `backend/src/main/java/com/college/attendance/service/AttendanceVerificationService.java`: `requirePending`, `markFailed`, `markSuccessful` |
| JWT/role boundary | `/api/face/**` requires `ROLE_STUDENT`; current student resolution comes from the JWT principal, not a client student identifier. | `backend/src/main/java/com/college/attendance/config/SecurityConfig.java`: `filterChain`; `FaceController.currentStudent` |
| Flask boundary | `POST /extract-embedding` uses constant-time `X-FACE-SERVICE-SECRET` validation. It processes input in memory and does not persist source images, crops, or embeddings. Legacy Flask routes are absent. | `face-service/app.py`: `_check_secret`, `extract_embedding_route`; `face-service/tests/test_flask.py`: `test_register_route_is_not_exposed`, `test_verify_route_is_not_exposed` |
| Face pipeline | It is a one-image pipeline: YuNet must find exactly one face; passive liveness runs; then and only then SFace aligns/extracts. | `face-service/face_pipeline.py`: `FaceVerificationPipeline.process`, `process_detected`; `face-service/face_utils.py`: `_require_exactly_one_face`, `detect_single_face_with_box` |
| Passive liveness | Face blur/brightness/size quality gates precede MiniFASNetV2 + V1SE inference. There is no temporal window, tracking, calibration, blink, pose, glasses, or challenge state. | `face-service/anti_spoof.py`: `_quality_metrics`, `_decide_quality`, `check_liveness`; `face-service/requirements.txt` |
| Match ordering | Flask returns an embedding only after `is_real=true`. Spring terminates on `is_real=false` before cosine calculation. | `face-service/app.py`: `extract_embedding_route`; `FaceVerificationPipeline.process_detected`; `FaceVerificationService.verifyStudentFace` |
| Encryption | Registered embeddings alone use AES-256-GCM; `FACE_EMB_KEY` is loaded, not generated or reset. | `backend/src/main/java/com/college/attendance/util/EncryptionUtil.java`: `loadKey`, `encrypt`, `decrypt` |

### Important absence finding

The active `face-service`, `backend`, and Android source contains no MediaPipe
dependency/import, `FaceLandmarker`, `ImageAnalysis`, active-liveness endpoint,
per-session challenge state, temporal blink/pose state, or glasses detector.
`face_pipeline.py` is testable passive still-image composition, not an
active-liveness scaffold. `PROJECT_CURRENT_STATE_AUDIT.md` also labels active
challenge-response as not implemented.

## Prototype feature disposition

| Supplied prototype feature | Current equivalent | Classification | Safe disposition |
|---|---|---|---|
| `landmarks.py`: MediaPipe VIDEO mode | None | Adapt | Add a server-side component only after dependency/model provenance review. |
| Blendshape blink; `solvePnP` yaw/pitch/roll | None | Adapt | Retain concepts/tests, but recalibrate landmarks, units and mirrored Android orientation. |
| Glasses evidence/hysteresis | None | Defer/adapt | Do not use as an attendance gate until consented false-rejection evaluation. |
| Per-session liveness state | Attendance attempts exist, but no video-liveness state | Adapt | Bind an active session to authenticated student and existing attempt. |
| 12-frame neutral calibration | None | Adapt | Store only short-lived ephemeral state. |
| Five-frame temporal MiniFASNet window | One `check_liveness` call per still | Adapt carefully | Wrap the present canonical inference; do not replace preprocessing. |
| Random one/two-step blink/head-turn, six-second timeout, one retry | Attempt expiry/retake only | Adapt | Spring generates/binds challenge; server enforces timeout/retry. |
| Face tracking | No temporal frames | Adapt | Use only inside active session; never let tracking bypass exact-one YuNet. |
| Identity after liveness | Already enforced | Reuse | Preserve Flask/Spring ordering. |
| Both MiniFASNet models, 0.65 default | Already implemented | Reuse current implementation | Preserve model decision and thresholds. |
| YuNet + aligned SFace | Already implemented | Reuse current implementation | Do not create a second detector/embedding path. |
| Local `FaceStorage` | Encrypted MySQL `FaceEmbedding` storage | Do not reuse | Retain existing Spring storage and AES-256-GCM. |
| Legacy `/register`, `/verify` | Retired Flask endpoints | Do not reuse | Keep Spring as the public policy boundary. |

## Preprocessing and ONNX compatibility

Both attendance ONNX models expose external `input [batch,80,80,3]` float and
`output [batch,3]` float contracts. The audited graphs have a leading internal
`Transpose(0,3,1,2)` and a terminal softmax. The current path supplies BGR,
raw `[0,255]`, `float32`, NHWC `[1,80,80,3]`, and does not apply softmax again.

| Topic | Current verified behavior | Integration requirement |
|---|---|---|
| Prototype NCHW transpose | Incompatible with the attendance ONNX external contract. | Never copy `np.transpose(crop, (2,0,1))`; call `anti_spoof.check_liveness` or its verified helpers. |
| Crops | V2 uses 2.7x and V1SE 4.0x `get_new_box` crops, then 80x80 linear resize. | Do not substitute a prototype crop/alignment. |
| Decision | Vectors are summed; class 1 must win and `sum[1]/2 >= 0.65`. | No threshold, class mapping, softmax, or aggregation change. |
| SFace | YuNet landmarks feed `SFACE_NET.alignCrop` only after liveness passes. | Keep SFace/cosine unreachable before authoritative liveness success. |

Evidence: `face-service/anti_spoof.py`: `_init_sessions`, `get_new_box`,
`crop_face_for_anti_spoof`, `check_liveness`; tests in
`face-service/tests/test_anti_spoof.py` assert float32, `[0,255]`, NHWC and no
external softmax.

## Session lifecycle and integration design

Current attendance attempts are strong replay/ownership controls but not active
video sessions. `AttendanceVerificationService.start` locks the student,
rate-limits to five attempts per ten minutes, and creates an expiring attempt.
`requirePending` locks it and marks it `VERIFYING`; `markFailed` consumes it;
`markSuccessful` creates the record after validation. There is currently no
frame sequence, active-state cleanup, temporal-session concurrency guard, or
challenge deadline.

### Recommended authoritative flow

1. Add **new authenticated Spring start/process/cancel endpoints**, never a
   Flask public endpoint. Start resolves the JWT principal, calls
   `requirePending` for attendance, and creates an opaque session bound to
   `(studentId, attemptId)`. Registration needs the same authenticated,
   short-lived intent, not a client-provided student ID.
2. Spring generates the random challenge with a CSPRNG and stores owner,
   purpose, server deadline, retry count, next sequence and terminal state.
   Before multi-instance deployment, use a transactional shared store or Redis;
   an unbounded Flask global dictionary is insufficient.
3. Android adds bounded CameraX `ImageAnalysis` (or a bounded capture loop) and
   submits rate-limited JPEG frames with opaque session ID and sequence to
   Spring. It renders challenge text only; it cannot assert blink/yaw/pass.
4. Spring checks JWT ownership, attempt state, server deadline, payload type and
   size, sequence monotonicity, one in-flight frame and rate limit. It forwards
   bytes plus an internal session ID to Flask with unchanged
   `FACE_SERVICE_SECRET`.
5. Flask holds only short-lived state keyed by that session ID, validates
   increasing MediaPipe VIDEO timestamps, evaluates landmarks and temporal
   MiniFASNet, and returns a server decision. It writes no frames, landmarks,
   crops or embeddings.
6. Only Spring records terminal success and calls the existing match path;
   expiry, failure, cancellation and retry remove ephemeral state. Geofence,
   duplicate attendance, attempt expiry and encrypted embedding architecture
   remain unchanged.

Use server receipt time for the six-second deadline. MediaPipe timestamps may be
strictly increasing per-session values for VIDEO mode, but client wall-clock time
must never extend the deadline or determine request ordering.

## Required safeguards

| Risk | Required safeguard |
|---|---|
| Direct Flask access or leaked service secret | Android never calls Flask; Spring alone sends `X-FACE-SERVICE-SECRET`. |
| Client-provided liveness | Client state is display-only; Flask/Spring decide all terminal states. |
| Frame replay/reordering | Bind opaque session to JWT/student/attempt; enforce server sequence, deadline, rate limit, one in-flight frame and cleanup. |
| Active challenge bypasses passive spoofing | Require the unchanged MiniFASNet decision as a mandatory gate; do not lower thresholds or disable a model. |
| Premature SFace/matching | No embedding or cosine until server-authoritative active and passive liveness passes. |
| Raw biometric retention | Keep frames, landmarks, crops and temporal scores ephemeral; retain only existing encrypted registered embedding. |
| Encryption migration | Preserve `EncryptionUtil`, AES-256-GCM, stored nonce/ciphertext/tag and `FACE_EMB_KEY`. |
| Attendance bypass | Preserve `requirePending`, geofence, rate limiting, duplicate checks and `markSuccessful` as the sole record creation path. |

## File-by-file integration map

| File/component | Future role | Explicit exclusion |
|---|---|---|
| `FaceCaptureActivity.java` | Bounded analysis producer and display-only challenge/progress UI. | No secrets, embeddings, direct Flask calls or client liveness authority. |
| `ApiService.java` | Authenticated Spring start/process/cancel calls. | Do not alter existing face API security semantics until covered by tests. |
| `FaceController.java` or new dedicated controller | Active-session orchestration, then existing verification. | Never create attendance from challenge result alone. |
| `AttendanceVerificationService.java` | Reuse attempt locks/expiry/ownership. | Do not loosen geofence, duplicate or attempt rules. |
| `FaceVerificationService.java` | Sole decrypt/cosine caller after liveness success. | Do not move matching/encryption to Flask/Android. |
| New `face-service/active_liveness.py` / adapted `landmarks.py` | Pure temporal state machine and MediaPipe adapter. | No NCHW conversion, local storage, raw-image persistence or legacy routes. |
| `anti_spoof.py` | Canonical per-frame inference. | No model/preprocess/class/threshold changes. |
| `FaceEmbedding` / `EncryptionUtil` | Existing encrypted storage. | Never introduce prototype `FaceStorage`. |

## Phased implementation plan

1. Define/test the Spring-owned opaque session protocol, deadlines, cancellation,
   idempotency and status vocabulary; do not add MediaPipe yet.
2. Implement Spring façade ownership, attempt binding, locks and cleanup using a
   fake internal liveness client.
3. Add a pure Flask active state machine and MediaPipe adapter with synthetic
   landmark observations. Delegate every MiniFASNet call to current code.
4. Add Android bounded-frame transport and display-only challenge UI, including
   rotation, backpressure and lifecycle cancellation tests.
5. Connect Spring-to-Flask internally; run concurrency, expiry, failure and
   consented genuine/print/screen-replay evaluation. No threshold change is in
   scope.
6. Review FRR/FAR, accessibility and capture-condition results before enabling
   active liveness as a release gate.

## Required tests

- MediaPipe timestamp monotonicity; reject missing, duplicate and decreasing
  values.
- Twelve-frame calibration; blink EMA open->closed->open; yaw/pitch/roll
  mirror mapping; three-frame stable head turn; random challenge/retry/timeout.
- Tracking loss and multiple faces: each accepted frame remains exactly one
  YuNet detection; tracking never overrides this rule.
- Temporal five-frame aggregation calls current MiniFASNet without NCHW,
  normalization or external-softmax drift.
- JWT ownership, attempt binding, expiry, cancellation, duplicate/out-of-order
  frames, malformed/oversized payloads, concurrent submissions and state cleanup.
- Wrong/missing Flask secret fails closed; Android cannot access Flask.
- Passive or active liveness rejection cannot reach SFace/cosine or attendance.
- Successful active/passive liveness still requires existing geofence, cosine,
  duplicate and attempt checks before `markSuccessful`.
- Registration continues to persist only AES-256-GCM encrypted embeddings.
- Preserve existing Flask tests, especially
  `test_spoof_rejection_never_invokes_sface_extraction`, plus Spring face and
  attendance-attempt tests.

## Conclusion

The prototype offers active-liveness concepts, but the current application is
passive, single-JPEG liveness only. The compatible path is additive:
Spring-mediated active sessions plus an internal Flask temporal component, while
retaining current NHWC MiniFASNet preprocessing, mandatory passive rejection,
SFace ordering, AES-256-GCM, JWT ownership, geofence and attendance safeguards.
Copying prototype NCHW tensor handling, local storage, legacy endpoints or
client-authoritative decisions is incompatible and must not be done.
