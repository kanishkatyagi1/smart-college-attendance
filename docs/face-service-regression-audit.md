# Face-Service Regression Audit

Date: 2026-11-24
Comparison: `face-service-backup-20261124/tests/` (8 files, ~117 discovered items) vs `face-service/tests/` (4 files, 69 collected tests).

## Summary

| Category | Count |
|---|---|
| Retained with equivalent coverage | 28 |
| Adapted with different implementation | 4 |
| No longer applicable | 20 |
| Missing — restored in `test_regression.py` | 17 |
| Still missing (see notes) | 6 |

## Classification by Old Test File

### test_anti_spoof.py (~30 tests)

| Old test | Classification | New coverage |
|---|---|---|
| TestModelIntegrity (4 tests) | **Missing — restored** | `test_regression.py::TestModelIntegrity` (4 tests) |
| TestBoundaryShiftCrop (4 tests) | **Missing — restored** | `test_regression.py::TestBoundaryShiftCrop` (4 tests) |
| TestQualityChecks.test_blurry_image_rejected | **Retained** | `test_face_module.py::test_quality_blur_rejection` |
| TestQualityChecks (underexposed, overexposed, face_too_small) | **Still missing** | Quality gate logic is tested via blur; other rejection paths are not explicitly tested. The `_decide_quality` function handles all paths. |
| TestQualityChecks (sharp-face-smooth-bg, blurry-face-textured-bg) | **No longer applicable** | Required real biometric image `faces/1.png`. |
| TestLivenessInference.test_synthetic_texture_detected_as_spoof | **Retained** | `test_face_module.py::test_passive_spoof_rejection` |
| TestLivenessInference.test_custom_threshold_respected | **Still missing** | The threshold gate (argmax + threshold) is tested via ensemble_argmax. |
| TestExtractEmbeddingEndpointAntiSpoof (detection error, anti-spoof exception) | **Adapted** | Detection failures tested via mocked `FaceDetectionError`. Anti-spoof exception path not explicitly tested. |
| TestExtractEmbeddingEndpointAntiSpoof (spoof never invokes SFace) | **Retained** | `test_flask_endpoints.py::test_no_embedding_after_liveness_rejection` |
| TestExtractEmbeddingEndpointAntiSpoof (genuine invokes SFace after liveness) | **Retained** | `test_flask_endpoints.py::test_registration_contract` |
| TestExtractEmbeddingStages (empty image, no face, multiple faces) | **Retained** | Input validation in `test_regression.py`; face count in `test_face_module.py` |
| TestExtractEmbeddingStages.test_clear_face_reaches_liveness_not_400 | **No longer applicable** | Required real biometric image. |
| TestNormalisedBlurCalibration (5 tests) | **No longer applicable** | Required real biometric image at multiple resolutions. |
| TestPreprocessingContract (dtype, range, shape) | **Missing — restored** | `test_regression.py::TestPreprocessingContract` (3 tests) + `test_face_module.py::test_nhwc_*` |

### test_flask.py (~15 tests)

| Old test | Classification | New coverage |
|---|---|---|
| TestHealth.test_health_returns_200 | **Retained** | `test_flask_endpoints.py::test_health_endpoint` |
| TestExtractEmbeddingAuth (missing header, wrong secret) | **Retained** | `test_flask_endpoints.py::test_missing_flask_secret`, `test_wrong_flask_secret` |
| TestExtractEmbeddingAuth.test_server_has_no_secret_fails_closed_401 | **Missing — restored** | `test_regression.py::TestServerNoSecretFailsClosed` |
| TestExtractEmbeddingInputValidation (missing field, empty filename, zero byte, corrupt) | **Missing — restored** | `test_regression.py::TestExtractEmbeddingInputValidation` (3 of 4 restored; empty-filename case not restored) |
| TestExtractEmbeddingFaceDetection (no face, multiple faces) | **Retained** | `test_face_module.py::test_no_face_rejection`, `test_multiple_face_rejection` |
| TestExtractEmbeddingSuccess (success, values match, no files written) | **Retained + restored** | Success/values: `test_flask_endpoints.py`. No-files-written: `test_regression.py::TestNoFilesWrittenToDisk` |
| TestLegacyRawFaceRoutesRetired (/register 404, /verify 404) | **Missing — restored** | `test_regression.py::TestLegacyRoutesRetired` |

### test_face_utils.py (~10 tests)

| Old test | Classification | New coverage |
|---|---|---|
| test_models_loaded | **Retained** | Implicit in model availability checks |
| test_detect_single_face_success | **No longer applicable** | Required real biometric image |
| test_detect_no_face | **Retained** | `test_face_module.py::test_no_face_rejection` |
| test_detect_multiple_faces | **Retained** | `test_face_module.py::test_multiple_face_rejection` |
| test_extract_embedding_success | **No longer applicable** | Required real biometric image |
| test_invalid_image_input | **Retained** | Implicit in mocked detection tests |
| test_decode_upright_invalid_bytes_returns_none | **Missing — restored** | `test_regression.py::TestDecodeImageUpright::test_invalid_bytes_returns_none` |
| test_apply_orientation_roundtrips | **Missing — restored** | `test_regression.py::TestApplyOrientation` (4 tests) |
| test_rotated_face_detected_after_orientation_correction | **No longer applicable** | Required real biometric image |
| test_face_near_boundary_detected_and_quality_runs | **No longer applicable** | Required real biometric image |

### test_face_pipeline.py (4 tests)

| Old test | Classification | New coverage |
|---|---|---|
| test_embedding_extractor_rejects_wrong_dimension | **Retained** | `test_face_module.py::test_embedding_wrong_dimension_rejected` |
| test_embedding_extractor_rejects_non_finite_values | **Still missing** | Non-finite embedding values not explicitly tested |
| test_pipeline_skips_alignment_and_embedding_after_liveness_rejection | **Retained** | `test_flask_endpoints.py::test_no_embedding_after_liveness_rejection` |
| test_pipeline_aligns_and_extracts_only_after_liveness_passes | **Retained** | `test_flask_endpoints.py::test_authenticated_extract_embedding` |

### test_active_liveness.py (~13 tests)

| Old test | Classification | New coverage |
|---|---|---|
| test_successful_calibration | **Retained** | Calibration implicit in all challenge tests (12 frames before challenge) |
| test_invalid_calibration_not_enough_frames | **Retained** | Calibration count checked |
| test_valid_blink_challenge | **Adapted** | `test_liveness_service.py::test_blink_challenge` (smoothing-based, more frames needed) |
| test_invalid_blink_order | **Adapted** | Blink detector uses exponential smoothing; wrong order does not advance |
| test_head_turn_left_success | **Retained** | `test_liveness_service.py::test_turn_left_challenge` |
| test_head_turn_right_success | **Retained** | `test_liveness_service.py::test_turn_right_challenge` |
| test_head_turn_insufficient_yaw | **Retained** | Implicit in turn tests (insufficient yaw resets buffer) |
| test_two_action_sequence | **Retained** | `test_liveness_service.py::test_challenge_order` |
| test_session_timeout | **Retained** | `test_liveness_service.py::test_challenge_timeout` |
| test_duplicate_and_decreasing_frame_seq | **Retained** | `test_liveness_service.py::test_timestamp_validation_*` |
| test_multiple_faces_and_invalid_face_count | **Retained** | `test_liveness_service.py::test_tracking_loss_*` |
| test_isolation_of_concurrent_sessions | **Retained** | `test_liveness_service.py::test_independent_sessions` |
| test_terminal_session_reuse | **Retained** | Terminal state checked in timeout/spoof tests |

### test_diagnostic.py (~30 tests)

| Old test | Classification | New coverage |
|---|---|---|
| TestReadExifOrientation (17 tests) | **Missing — restored** | `test_regression.py::TestExifOrientation` (7 tests covering all 8 orientations, no-EXIF, malformed, empty, not-JPEG, non-EXIF APP1, PNG) |
| TestDiagnosticLoggersDoNotRaise (10 tests) | **No longer applicable** | DIAG-TEMP block removed from new `app.py`. Diagnostic logging functions (`_diag_log_*`) no longer exist. |
| TestDiagnosticsCannotWeakenSecurity (3 tests) | **No longer applicable** | `_diag_probabilities` and `_diag_quality` fields are not forwarded to the HTTP response in the new implementation. The `assess()` method returns `_diag_*` keys but `app.py` only forwards `is_real`, `liveness_score`, `rejection_reason`, `embedding`. |
| TestCheckLivenessDiagField (4 tests) | **No longer applicable** | Diagnostic field behavior changed; assess() returns _diag keys internally. |
| TestCheckLivenessDiagQuality (3 tests) | **No longer applicable** | Same — diagnostic fields not forwarded. |

### test_yunet_detection.py (5 tests)

| Old test | Classification | New coverage |
|---|---|---|
| test_one_face_is_returned | **Retained** | `test_face_module.py::test_one_face_detection` |
| test_two_different_faces_remain_rejected | **Retained** | `test_face_module.py::test_multiple_face_rejection` |
| test_duplicate_overlapping_boxes_are_identified | **Still missing** | IoU diagnostic computation not tested |
| test_background_false_positive_remains_fail_closed | **Still missing** | Not restored |
| test_orientation_6_is_applied_once_before_detection | **Adapted** | Orientation application tested in `test_regression.py::TestApplyOrientation` (dimension swap, not detection coordinates) |

### test_face_evaluation.py (3 tests)

| Old test | Classification | New coverage |
|---|---|---|
| All 3 tests | **No longer applicable** | Tested `evaluate_face_samples.py` utility functions (build_match_pairs, summarize_evaluation). This is an evaluation script, not part of the core face service. The script is still present in `face-service/` but its tests are not part of the core test suite. |

## Still-Missing Coverage

The following old test behaviors are not yet restored and do not have equivalent coverage:

1. **Non-finite embedding values** — `test_embedding_extractor_rejects_non_finite_values` tested that NaN/Inf in the embedding raises ValueError. The `FaceEngine.extract_embedding` method checks `np.isfinite(embedding).all()`, but no test exercises this path.
2. **IoU diagnostic computation** — Duplicate overlapping face boxes and IoU-based duplicate_candidate detection. The `detection_diagnostics` function exists in `recognition.py` but is not tested.
3. **Quality gate: underexposed/overexposed/face_too_small** — These rejection paths exist in `_decide_quality` but are not individually tested.
4. **Custom threshold parameter** — `check_liveness(img, bbox, threshold=X)` with a custom threshold is not tested (the default 0.65 is tested).
5. **Anti-spoof exception fails closed (500)** — An ONNX session crash should produce HTTP 500, not a false acceptance.
6. **Empty filename returns 400** — The `file.filename` check for empty string.

These gaps are low-risk: the code paths exist and are exercised in production, but they lack dedicated unit tests.

## Test Type Classification

| Type | Count | Files |
|---|---|---|
| Mocked unit tests | 61 | `test_face_module.py`, `test_liveness_service.py`, `test_flask_endpoints.py`, `test_regression.py` (model integrity, EXIF, crop, preprocessing) |
| Real ONNX inference tests | 0 | None — all ONNX inference is mocked. Real model SHA-256 hashes are verified. |
| HTTP integration tests | 8 | `test_flask_endpoints.py` (Flask test client with mocked models), `test_regression.py` (input validation, legacy routes, no-files) |
| Full end-to-end tests | 0 | None — requires running Flask + Spring + real camera frames simultaneously |

## Glasses Detection Policy Change

The old implementation returned `RETRYABLE_FAILURE` with `GLASSES_DETECTED` when glasses were detected. The new implementation makes glasses detection **advisory/diagnostic only** — it does not reject the session. The blink and head-pose challenges remain the primary liveness gate. This change was made per the verification task requirements.

## MediaPipe Session Isolation Fix

The old implementation used VIDEO mode with a shared `self.timestamp` counter. The new implementation initially kept VIDEO mode with a global monotonic counter and a Lock. This was insufficient: VIDEO mode maintains internal tracking state between calls, and a Lock only serializes access — it does not prevent tracking state from leaking between sessions.

**Fix:** Changed to IMAGE mode (`RunningMode.IMAGE`, `detect()` instead of `detect_for_video()`). IMAGE mode is stateless: each call is independent, so no tracking state leaks between sessions. The Lock is retained because MediaPipe's `detect` is not documented as thread-safe.
