# Face subsystem source audit

Date: 2026-11-24

## Confirmed findings

1. The active Spring Boot registration and verification paths both call the authenticated Flask `POST /extract-embedding` endpoint.  They therefore use the same YuNet detection, MiniFASNet liveness gate, SFace alignment and 128-element embedding extraction path.
2. Embeddings are encrypted with AES-256-GCM before persistence.  Verification decrypts the stored 128-float representation, validates it, and calculates cosine similarity with the configured 0.60 baseline.  Attendance is only persisted after geofence, liveness and similarity checks pass.
3. MiniFASNetV2 and MiniFASNetV1SE model hashes match the pinned release hashes.  The local ONNX graphs accept `FLOAT [batch,80,80,3]`, perform an internal NHWC-to-NCHW transpose, and end in Softmax.  The existing preprocessing uses BGR `float32` values in the `[0,255]` range and the reference 2.7/4.0 crop scales.
4. The supplied failure had `liveness_score=0.0116`, so the two models' real-face probabilities sum to approximately `0.0232`.  Spring correctly stopped before SFace and cosine matching.  That aggregate score cannot identify either model's individual result or the physical cause of the rejection.
5. The former Flask `/register` and `/verify` endpoints had no internal-service authentication and the registration route wrote a grayscale face crop to `face-service/faces`.  They were not called by Android, which uses Spring Boot, and have now been retired.  The active Flask interface is limited to `/health` and authenticated `/extract-embedding`.
6. `FaceCaptureActivity` configures `ImageCapture` target rotation only when the camera starts.  Android can recreate on rotation, but the active capture use case is not updated while the activity remains alive.  This is a capture-orientation defect.

## Unverified assumptions

- No genuine Android capture with documented consent and ground truth is available in this workspace.  A low MiniFASNet score therefore cannot be attributed to image orientation, model suitability, lighting, pose, crop, or a presentation attack.
- Existing `faces/1.png` has no ground-truth label.  It must not be treated as a genuine-live acceptance sample.
- The current blur, brightness and face-size thresholds have unit coverage but no documented false-accept or false-reject evaluation dataset.

## Safe rebuild sequence

1. Retire the unused raw-image Flask endpoints and keep only `/health` and authenticated `/extract-embedding`.
2. Make Android preview and capture target rotation consistent and retain JPEG EXIF normalization in Flask.
3. Move the Flask execution path behind independently testable decoder, detector, quality, liveness, alignment and embedding components while preserving the service response contract.
4. Add a consented evaluation harness that reports aggregate liveness and matching metrics from a caller-supplied manifest.  It must not ship sample biometric images or alter runtime decisions.
5. Use documented genuine and attack samples to determine whether MiniFASNet is suitable.  Until then, retain fail-closed liveness behavior and do not claim production-grade presentation-attack protection.

## Rebuild progress

- Completed: CameraX updates preview and capture rotation from the current display; Flask continues to normalize JPEG EXIF orientation before image processing.
- Completed: the unused Flask raw-image `/register` and `/verify` routes have been retired.  Raw captures and embeddings are not persisted by Flask.
- Completed: `face_pipeline.py` separates YuNet detection, MiniFASNet liveness, SFace alignment, and SFace embedding extraction.  The extractor validates exactly 128 finite values before the existing Spring Boot encrypted-storage or matching flows receive a result.
- Pending evidence: liveness suitability and matching calibration require consented, labelled multi-device genuine, print, screen-replay, and video-replay samples.  No threshold or policy change is justified by the current fixture.
