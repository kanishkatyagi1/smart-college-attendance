# Face Service

Python Flask microservice for face detection, passive anti-spoofing, active
liveness, and SFace embedding extraction.  Called only by the Spring Boot
backend; never exposed directly to Android.

## Architecture

```
Android (CameraX)  →  Spring Boot (JWT, geofence, AES-256-GCM storage)
                         ↓ X-FACE-SERVICE-SECRET
                    Flask Face Service
                         ├── /extract-embedding    (passive anti-spoof + SFace)
                         └── /active-liveness/process  (MediaPipe + challenge validation)
```

### Module layout

```
face-service/
  app.py                     Flask endpoints
  face_module/
    config.py                Environment-driven configuration
    recognition.py           YuNet detection + SFace 128-dim embedding
    antispoof.py             MiniFASNetV2+V1SE ensemble (NHWC preprocessing)
    landmarks.py             MediaPipe Face Landmarker (VIDEO mode)
    glasses.py               Temporal glasses detection gate
    liveness_service.py      Per-session active-liveness state machine
  anti_spoof.py              Backward-compat wrapper → face_module.antispoof
  face_utils.py              Backward-compat wrapper → face_module.recognition
  face_pipeline.py           Backward-compat wrapper → FaceVerificationPipeline
  models/                    ONNX + MediaPipe model assets
  tests/                     Unit + integration tests
```

## Model compatibility (Phase 6)

MiniFASNet input contract (verified line-by-line against upstream):

| Property | Value |
|---|---|
| Channel order | BGR |
| Data type | float32 |
| Value range | [0, 255] raw (no normalization) |
| Tensor layout | **NHWC** [1, 80, 80, 3] |
| V2 crop scale | 2.7 |
| V1SE crop scale | 4.0 |
| Softmax | Baked into ONNX graph (do not re-apply) |
| Output classes | [print=0, real=1, replay=2] |
| Liveness score | ensemble[1] / 2 |
| Liveness threshold | 0.65 (argmax + threshold gate) |

The ONNX graphs contain a leading `Transpose(perm=[0,3,1,2])` that converts
NHWC→NCHW internally.  The external contract is NHWC; do **not** transpose
to NCHW before inference (the archived prototype's `np.transpose(crop, (2,0,1))`
was wrong for these models).

## Active liveness

Spring Boot owns the session lifecycle (session ID, student ownership,
challenge sequence, deadline, retry limit, frame sequence).  Flask processes
frames and returns decisions; it never generates its own challenges.

State machine per session:

```
CALIBRATING (12 frames)  →  CHALLENGE  →  PASSED / FAILED
```

- **Calibration**: collect 12 yaw measurements for a neutral baseline.
- **Passive anti-spoof**: temporal window (5 frames) checked on every frame.
  If mean score < 0.65, returns FAILED immediately (before challenge validation).
- **Challenges** (from Spring): BLINK (OPEN→CLOSED→OPEN), TURN_LEFT (yaw < -12°),
  TURN_RIGHT (yaw > +12°).  Each requires 3 stable frames.
- **Glasses**: advisory/diagnostic only.  Glasses detection does NOT reject
  the session.  Blink and head-pose challenges are the sole liveness gate.
  Glasses state is tracked for future UX guidance but has no effect on decisions.

### Session concurrency

- Bounded in-memory store (default 1000 sessions).
- Per-session `Lock` ensures one in-flight processing operation.
- MediaPipe uses **IMAGE mode** (stateless `detect()`), not VIDEO mode.
  IMAGE mode prevents tracking-state leakage between concurrent sessions.
  A Lock serialises calls because MediaPipe's `detect` is not documented
  as thread-safe.
- Expired and terminal sessions are cleaned up after a retention period.
- Frame sequences must be strictly monotonic.
- **Single-instance deployment — critical warning:**
  - The Flask face-service MUST run as a **single process** (one worker).
    Multiple Flask workers (e.g. `gunicorn -w 4`) or multiple instances
    behind a load balancer will NOT share session state.  Active-liveness
    sessions are in-memory and per-process.  A frame sent to worker B will
    not find the session created on worker A, causing a fresh calibration
    and incorrect liveness decisions.
  - Before enabling multiple workers or instances, replace the in-memory
    session store with a shared atomic store (e.g. Redis with CAS operations)
    that provides atomic ownership, sequence, and in-flight transitions
    across processes.
  - The Spring backend's `ActiveLivenessSessionService` has the same
    single-instance constraint — its in-memory session registry is not
    distributed.

## Endpoints

### `POST /extract-embedding`

Header: `X-FACE-SERVICE-SECRET`

Multipart: `image` (JPEG)

Response (success):
```json
{"extracted": true, "is_real": true, "liveness_score": 0.76, "embedding": [...128 floats]}
```

Response (rejection):
```json
{"extracted": false, "is_real": false, "liveness_score": 0.0,
 "rejection_reason": "SPOOF_DETECTED", "reason": "SPOOF", "error": "..."}
```

### `POST /active-liveness/process`

Header: `X-FACE-SERVICE-SECRET`

Multipart: `frame` (JPEG), `session_id` (str), `challenge_seq` (JSON array),
`frame_seq` (int)

Response:
```json
{"decision": "PENDING", "reason": null}
```

Decision values: `PENDING`, `RETRYABLE_FAILURE`, `FAILED`, `PASSED`.
Maps directly to Spring's `ActiveLivenessEvaluator.Decision`.

## Running

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
# Download models (YuNet, SFace, MiniFASNet, MediaPipe) into models/
set FACE_SERVICE_SECRET=<secret>
python app.py
```

## Testing

```bash
pytest tests/ -v
```

Tests use synthetic observations and mocked model outputs; they do not depend
on biometric photographs.
