# Active Liveness Session Protocol — Phase 1

## Scope

This phase introduces only a Spring-owned session protocol. It has no Android
frame producer, MediaPipe, Flask active-liveness endpoint, model/preprocessing
change, or attendance-marking action.

The default internal evaluator returns `PENDING` for every valid frame. It is a
fail-closed placeholder and cannot create an attendance record.

## Authenticated endpoints

All endpoints are beneath `/api/face/**`, therefore require the existing JWT
student authorization. The authenticated principal selects the student; no
request contains a student ID.

| Method | Endpoint | Input | Result |
|---|---|---|---|
| `POST` | `/api/face/active-liveness/sessions` | JSON `{purpose: REGISTRATION|VERIFICATION, attemptId?: string}` | Server-generated opaque session ID, one/two-action challenge, deadline and status. Verification requires an existing pending attempt; registration rejects `attemptId`. |
| `POST` | `/api/face/active-liveness/sessions/{id}/frames?sequence=n` | multipart `frame`, JPEG only | Server validates ownership, deadline, exact sequence, size and rate before passing bytes to the internal evaluator. |
| `GET` | `/api/face/active-liveness/sessions/{id}` | none | Safe state view only; never raw frame, landmark, embedding or secret. |
| `DELETE` | `/api/face/active-liveness/sessions/{id}` | none | Marks an active session `CANCELLED`. |

Challenges contain server-selected `BLINK`, `TURN_LEFT`, and/or `TURN_RIGHT`.
The client may display them but cannot submit a completion assertion.

## States and cleanup

`ACTIVE -> PROCESSING -> ACTIVE` is the normal placeholder path.
Terminal states are `COMPLETED`, `FAILED`, `CANCELLED`, and `EXPIRED`; all reject
new frames. Terminal metadata is retained only for the configured 60 seconds,
then removed. Expired active state is converted to `EXPIRED` using server time.
Cleanup runs before session creation and periodically.

The registry is bounded in memory (`1000` sessions by default), locks on each
session for one frame in flight, enforces exact monotonic sequences, JPEG-only
payloads up to 1 MiB, 100 ms minimum frame interval, 30 frames and one retry.

This is **single-instance only**. Before the endpoint is enabled behind multiple
application instances, replace the registry with a shared store that provides
atomic ownership/sequence/in-flight transitions and shared expiry cleanup.

## Attendance boundary

For verification, startup binds to the existing authenticated pending attendance
attempt through `AttendanceVerificationService.requirePending`. Phase 1 does not
continue that attempt into face matching and never calls `markSuccessful`.
Future work must require active success *and* the unchanged passive MiniFASNet
gate, SFace/cosine match, geofence, duplicate protection and existing attempt
checks before the only attendance-record path, `markSuccessful`.
