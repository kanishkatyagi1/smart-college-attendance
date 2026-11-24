"""Active liveness state machine tests (tests 9-18).

All tests use mocked model dependencies; no real ONNX or MediaPipe models
are required.  LandmarkFrame sequences are synthesised to drive the state
machine deterministically.
"""
from __future__ import annotations

import threading
from dataclasses import replace
from unittest.mock import patch

import numpy as np
import pytest

from face_module.recognition import FaceDetectionError, FaceError
from face_module.liveness_service import Decision, LivenessSession
from tests.conftest import make_frame


# Helper: a complete blink sequence (OPEN -> CLOSED -> OPEN).
# Smoothing (0.45*new + 0.55*old) requires several OPEN frames after CLOSED
# for the smoothed value to drop below the 'opened' threshold (0.16).
BLINK_SEQ = [
    make_frame(blink_left=0.0, blink_right=0.0),   # OPEN (start)
    make_frame(blink_left=0.9, blink_right=0.9),   # CLOSED
    make_frame(blink_left=0.9, blink_right=0.9),   # CLOSED (2nd frame)
    make_frame(blink_left=0.0, blink_right=0.0),   # OPEN (opening)
    make_frame(blink_left=0.0, blink_right=0.0),   # OPEN
    make_frame(blink_left=0.0, blink_right=0.0),   # OPEN (fully open) -> PASSED
]

TURN_LEFT_SEQ = [make_frame(yaw=-15.0) for _ in range(3)]
TURN_RIGHT_SEQ = [make_frame(yaw=15.0) for _ in range(3)]


# -- Test 9: Blink challenge -------------------------------------------------

def test_blink_challenge(liveness_service, mock_landmarks):
    """Test 9: Blink challenge passes with OPEN -> CLOSED -> OPEN pattern."""
    session_id = "blink-test"
    challenge_seq = ["BLINK"]

    calib = [make_frame(yaw=0.0) for _ in range(12)]
    mock_landmarks.analyze.side_effect = calib + BLINK_SEQ

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    decisions = []
    for i in range(len(calib) + len(BLINK_SEQ)):
        d, _ = liveness_service.process_frame(session_id, challenge_seq, i, img)
        decisions.append(d)

    assert all(d == Decision.PENDING for d in decisions[:12])
    assert all(d == Decision.PENDING for d in decisions[12:-1])
    assert decisions[-1] == Decision.PASSED


# -- Test 10: Left head-turn challenge ---------------------------------------

def test_turn_left_challenge(liveness_service, mock_landmarks):
    """Test 10: Turn-left challenge passes with yaw < neutral - 12 deg."""
    session_id = "turn-left-test"
    challenge_seq = ["TURN_LEFT"]

    calib = [make_frame(yaw=0.0) for _ in range(12)]
    mock_landmarks.analyze.side_effect = calib + TURN_LEFT_SEQ

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    for i in range(len(calib) + len(TURN_LEFT_SEQ)):
        d, _ = liveness_service.process_frame(session_id, challenge_seq, i, img)

    assert d == Decision.PASSED


# -- Test 11: Right head-turn challenge --------------------------------------

def test_turn_right_challenge(liveness_service, mock_landmarks):
    """Test 11: Turn-right challenge passes with yaw > neutral + 12 deg."""
    session_id = "turn-right-test"
    challenge_seq = ["TURN_RIGHT"]

    calib = [make_frame(yaw=0.0) for _ in range(12)]
    mock_landmarks.analyze.side_effect = calib + TURN_RIGHT_SEQ

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    for i in range(len(calib) + len(TURN_RIGHT_SEQ)):
        d, _ = liveness_service.process_frame(session_id, challenge_seq, i, img)

    assert d == Decision.PASSED


# -- Test 12: Challenge order ------------------------------------------------

def test_challenge_order(liveness_service, mock_landmarks):
    """Test 12: Multiple challenges must pass in the given order [TURN_LEFT, BLINK]."""
    session_id = "order-test"
    challenge_seq = ["TURN_LEFT", "BLINK"]

    calib = [make_frame(yaw=0.0) for _ in range(12)]
    all_frames = calib + TURN_LEFT_SEQ + BLINK_SEQ
    mock_landmarks.analyze.side_effect = all_frames

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    for i in range(len(all_frames)):
        d, _ = liveness_service.process_frame(session_id, challenge_seq, i, img)

    assert d == Decision.PASSED


def test_challenge_order_reversed(liveness_service, mock_landmarks):
    """Test 12b: Reversed order [BLINK, TURN_LEFT] must also work."""
    session_id = "order-rev-test"
    challenge_seq = ["BLINK", "TURN_LEFT"]

    calib = [make_frame(yaw=0.0) for _ in range(12)]
    all_frames = calib + BLINK_SEQ + TURN_LEFT_SEQ
    mock_landmarks.analyze.side_effect = all_frames

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    for i in range(len(all_frames)):
        d, _ = liveness_service.process_frame(session_id, challenge_seq, i, img)

    assert d == Decision.PASSED


# -- Test 13: Challenge timeout ----------------------------------------------

def test_challenge_timeout(liveness_service, mock_landmarks, clock):
    """Test 13: Session expires when clock advances past the timeout."""
    session_id = "timeout-test"
    challenge_seq = ["BLINK"]
    config = liveness_service.config

    # Provide enough frames for calibration + the post-expiry call
    mock_landmarks.analyze.side_effect = [make_frame(yaw=0.0) for _ in range(20)]

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    for i in range(12):
        d, _ = liveness_service.process_frame(session_id, challenge_seq, i, img)
        assert d == Decision.PENDING

    # Advance clock past the session timeout
    clock.advance(config.liveness_session_timeout_seconds + 1)

    d, reason = liveness_service.process_frame(session_id, challenge_seq, 12, img)
    assert d == Decision.FAILED
    assert reason == "SESSION_EXPIRED"


# -- Test 14: Retry policy ---------------------------------------------------

def test_retry_policy_challenge_change(liveness_service, mock_landmarks):
    """Test 14: Spring retry replaces challenges; Flask resets progress but keeps calibration."""
    session_id = "retry-test"
    challenge_seq_1 = ["TURN_LEFT"]
    challenge_seq_2 = ["BLINK"]

    # Calibrate, then start turn-left but don't complete (only 1 frame)
    calib = [make_frame(yaw=0.0) for _ in range(12)]
    partial_turn = [make_frame(yaw=-15.0)]
    mock_landmarks.analyze.side_effect = calib + partial_turn

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    for i in range(len(calib) + len(partial_turn)):
        d, _ = liveness_service.process_frame(session_id, challenge_seq_1, i, img)
    assert d == Decision.PENDING  # challenge not yet complete

    # Spring sends a NEW challenge sequence (retry); Flask detects and resets
    mock_landmarks.analyze.side_effect = BLINK_SEQ
    for j in range(len(BLINK_SEQ)):
        d, _ = liveness_service.process_frame(
            session_id, challenge_seq_2,
            len(calib) + len(partial_turn) + j, img
        )

    assert d == Decision.PASSED


# -- Test 15: Tracking loss --------------------------------------------------

def test_tracking_loss_no_face(liveness_service, mock_engine):
    """Test 15: No face detected returns RETRYABLE_FAILURE."""
    mock_engine.detect_single_face_with_box.side_effect = FaceDetectionError(
        "No face detected", "NO_FACE", None
    )
    img = np.zeros((480, 640, 3), dtype=np.uint8)
    d, reason = liveness_service.process_frame("track-test", ["BLINK"], 0, img)
    assert d == Decision.RETRYABLE_FAILURE
    assert reason == "NO_FACE"


def test_tracking_loss_multiple_faces(liveness_service, mock_engine):
    """Test 15b: Multiple faces returns RETRYABLE_FAILURE."""
    mock_engine.detect_single_face_with_box.side_effect = FaceDetectionError(
        "Expected exactly one face, found 2", "MULTIPLE_FACES", None
    )
    img = np.zeros((480, 640, 3), dtype=np.uint8)
    d, reason = liveness_service.process_frame("track-test2", ["BLINK"], 0, img)
    assert d == Decision.RETRYABLE_FAILURE
    assert reason == "MULTIPLE_FACES"


# -- Test 16: Independent sessions -------------------------------------------

def test_independent_sessions(liveness_service, mock_landmarks):
    """Test 16: Two sessions do not interfere with each other."""
    sid_a = "session-a"
    sid_b = "session-b"
    seq_a = ["BLINK"]
    seq_b = ["TURN_RIGHT"]

    img = np.zeros((480, 640, 3), dtype=np.uint8)

    # Session A: calibrate + blink
    calib_a = [make_frame(yaw=0.0) for _ in range(12)]
    mock_landmarks.analyze.side_effect = calib_a + BLINK_SEQ
    for i in range(len(calib_a) + len(BLINK_SEQ)):
        d_a, _ = liveness_service.process_frame(sid_a, seq_a, i, img)
    assert d_a == Decision.PASSED

    # Session B: calibrate + turn right (different challenge, different data)
    calib_b = [make_frame(yaw=0.0) for _ in range(12)]
    mock_landmarks.analyze.side_effect = calib_b + TURN_RIGHT_SEQ
    for i in range(len(calib_b) + len(TURN_RIGHT_SEQ)):
        d_b, _ = liveness_service.process_frame(sid_b, seq_b, i, img)
    assert d_b == Decision.PASSED


# -- Test 17: Concurrent frame handling --------------------------------------

def test_concurrent_frame_handling(liveness_service, mock_landmarks):
    """Test 17: Per-session lock prevents concurrent state corruption."""
    session_id = "concurrent-test"
    challenge_seq = ["BLINK"]

    # Provide many frames (no actual blink occurs, so all should be PENDING)
    mock_landmarks.analyze.side_effect = [make_frame(yaw=0.0) for _ in range(100)]

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    results = []
    lock = threading.Lock()

    def worker(start_seq):
        for i in range(start_seq, start_seq + 10):
            d, _ = liveness_service.process_frame(session_id, challenge_seq, i, img)
            with lock:
                results.append(d)

    t1 = threading.Thread(target=worker, args=(0,))
    t2 = threading.Thread(target=worker, args=(10,))
    t1.start()
    t2.start()
    t1.join(timeout=10)
    t2.join(timeout=10)

    # All results should be valid Decision values (no corruption)
    assert all(isinstance(d, Decision) for d in results)
    # No frame should have PASSED (no actual blink occurred)
    assert all(d != Decision.PASSED for d in results)


# -- Test 18: Timestamp validation -------------------------------------------

def test_timestamp_validation_duplicate(liveness_service, mock_landmarks):
    """Test 18: Duplicate frame sequence is rejected."""
    session_id = "ts-dup-test"
    challenge_seq = ["BLINK"]

    mock_landmarks.analyze.side_effect = [make_frame(yaw=0.0) for _ in range(20)]
    img = np.zeros((480, 640, 3), dtype=np.uint8)
    liveness_service.process_frame(session_id, challenge_seq, 0, img)
    liveness_service.process_frame(session_id, challenge_seq, 1, img)
    # Duplicate frame seq=1
    d, reason = liveness_service.process_frame(session_id, challenge_seq, 1, img)
    assert d == Decision.RETRYABLE_FAILURE
    assert reason == "OUT_OF_ORDER_FRAME"


def test_timestamp_validation_decreasing(liveness_service, mock_landmarks):
    """Test 18b: Decreasing frame sequence is rejected."""
    session_id = "ts-dec-test"
    challenge_seq = ["BLINK"]

    mock_landmarks.analyze.side_effect = [make_frame(yaw=0.0) for _ in range(20)]
    img = np.zeros((480, 640, 3), dtype=np.uint8)
    liveness_service.process_frame(session_id, challenge_seq, 5, img)
    d, reason = liveness_service.process_frame(session_id, challenge_seq, 3, img)
    assert d == Decision.RETRYABLE_FAILURE
    assert reason == "OUT_OF_ORDER_FRAME"


# -- Bonus: Passive anti-spoof never overridden -------------------------------

def test_spoof_rejection_overrides_challenge(liveness_service, mock_anti_spoof,
                                               mock_landmarks):
    """Active challenge success must never override passive anti-spoof rejection."""
    session_id = "spoof-override-test"
    challenge_seq = ["BLINK"]

    # Calibrate with genuine scores
    calib = [make_frame(yaw=0.0) for _ in range(12)]
    mock_landmarks.analyze.side_effect = calib

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    for i in range(12):
        liveness_service.process_frame(session_id, challenge_seq, i, img)

    # Now inject spoof scores (below threshold) — enough to fill the anti-spoof window
    mock_anti_spoof.score.return_value = 0.1
    spoof_frames = [make_frame(blink_left=0.0, blink_right=0.0) for _ in range(10)]
    mock_landmarks.analyze.side_effect = spoof_frames

    failed = False
    for i in range(len(spoof_frames)):
        d, reason = liveness_service.process_frame(session_id, challenge_seq, 12 + i, img)
        if d == Decision.FAILED and reason == "SPOOF_DETECTED":
            failed = True
            break

    assert failed, "Expected SPOOF_DETECTED when anti-spoof window fills with low scores"


# ---------------------------------------------------------------------------
# Development-only MiniFASNet bypass during active liveness
#
# When config.disable_passive_antispoof is True (FLASK_ENV=development +
# DISABLE_PASSIVE_ANTISPOOF=true), LivenessService.process_frame() must skip
# MiniFASNet score() entirely.  LivenessSession.process() must skip the
# temporal anti-spoof check.  YuNet detection, quality gate, MediaPipe
# landmarks, and blink/head-turn challenges all run unchanged.
# ---------------------------------------------------------------------------


@pytest.fixture
def bypass_config(config):
    """Config with disable_passive_antispoof=True (dev bypass active)."""
    return replace(config, disable_passive_antispoof=True)


@pytest.fixture
def liveness_service_bypass(mock_engine, mock_anti_spoof, mock_landmarks,
                            bypass_config, clock):
    """LivenessService with the dev MiniFASNet bypass enabled."""
    from face_module.liveness_service import LivenessService
    return LivenessService(bypass_config, mock_engine, mock_landmarks,
                           mock_anti_spoof, clock=clock)


class TestDevBypassScoreSkipped:
    """MiniFASNet score() is never called when the bypass is active."""

    def test_score_never_called_during_liveness(self, liveness_service_bypass,
                                                mock_anti_spoof, mock_landmarks):
        """score() must not be invoked on any frame when bypass is active."""
        session_id = "bypass-score-test"
        challenge_seq = ["BLINK"]
        mock_landmarks.analyze.side_effect = [make_frame(yaw=0.0) for _ in range(15)]
        img = np.zeros((480, 640, 3), dtype=np.uint8)

        for i in range(15):
            liveness_service_bypass.process_frame(session_id, challenge_seq, i, img)

        mock_anti_spoof.score.assert_not_called()

    def test_quality_check_still_runs(self, liveness_service_bypass,
                                       mock_anti_spoof, mock_landmarks):
        """Quality gate (check_quality) must still run when bypass is active."""
        session_id = "bypass-quality-test"
        challenge_seq = ["BLINK"]
        mock_landmarks.analyze.side_effect = [make_frame(yaw=0.0) for _ in range(15)]
        img = np.zeros((480, 640, 3), dtype=np.uint8)

        for i in range(15):
            liveness_service_bypass.process_frame(session_id, challenge_seq, i, img)

        mock_anti_spoof.check_quality.assert_called()

    def test_face_detection_still_runs(self, liveness_service_bypass,
                                        mock_engine, mock_landmarks):
        """YuNet single-face detection must still run when bypass is active."""
        session_id = "bypass-detect-test"
        challenge_seq = ["BLINK"]
        mock_landmarks.analyze.side_effect = [make_frame(yaw=0.0) for _ in range(15)]
        img = np.zeros((480, 640, 3), dtype=np.uint8)

        for i in range(5):
            liveness_service_bypass.process_frame(session_id, challenge_seq, i, img)

        mock_engine.detect_single_face_with_box.assert_called()

    def test_quality_failure_still_rejected(self, liveness_service_bypass,
                                             mock_anti_spoof, mock_landmarks):
        """A quality-gate failure must still return RETRYABLE_FAILURE."""
        session_id = "bypass-qfail-test"
        challenge_seq = ["BLINK"]
        mock_anti_spoof.check_quality.return_value = "BLURRY_IMAGE"
        mock_landmarks.analyze.side_effect = [make_frame(yaw=0.0) for _ in range(5)]
        img = np.zeros((480, 640, 3), dtype=np.uint8)

        d, reason = liveness_service_bypass.process_frame(
            session_id, challenge_seq, 0, img)

        assert d == Decision.RETRYABLE_FAILURE
        assert reason == "BLURRY_IMAGE"


class TestDevBypassTemporalSpoofSkipped:
    """Temporal spoof rejection is skipped only in bypass mode."""

    def test_no_spoof_detected_after_window_with_bypass(
            self, liveness_service_bypass, mock_anti_spoof, mock_landmarks):
        """With bypass active, processing many frames must NOT produce
        SPOOF_DETECTED even if anti_spoof.score would return low values."""
        session_id = "bypass-spoof-test"
        challenge_seq = ["BLINK"]
        # score() is never called (bypass active), so even if we set a low
        # return value it doesn't matter — the temporal window is skipped.
        mock_anti_spoof.score.return_value = 0.1
        mock_landmarks.analyze.side_effect = [make_frame(yaw=0.0) for _ in range(20)]
        img = np.zeros((480, 640, 3), dtype=np.uint8)

        for i in range(20):
            d, reason = liveness_service_bypass.process_frame(
                session_id, challenge_seq, i, img)
            assert d != Decision.FAILED or reason != "SPOOF_DETECTED", \
                "SPOOF_DETECTED must not fire when bypass is active"

    def test_production_still_rejects_spoof(self, liveness_service,
                                            mock_anti_spoof, mock_landmarks):
        """Without bypass (production), low spoof scores must still trigger
        SPOOF_DETECTED after the anti-spoof window fills."""
        session_id = "prod-spoof-test"
        challenge_seq = ["BLINK"]
        mock_anti_spoof.score.return_value = 0.1  # well below 0.65
        mock_landmarks.analyze.side_effect = [make_frame(yaw=0.0) for _ in range(20)]
        img = np.zeros((480, 640, 3), dtype=np.uint8)

        spoof_detected = False
        for i in range(20):
            d, reason = liveness_service.process_frame(
                session_id, challenge_seq, i, img)
            if d == Decision.FAILED and reason == "SPOOF_DETECTED":
                spoof_detected = True
                break

        assert spoof_detected, "Production must enforce temporal anti-spoof"


class TestDevBypassChallengesStillRequired:
    """Active liveness challenges must still complete when bypass is active."""

    def test_blink_challenge_passes_with_bypass(self, liveness_service_bypass,
                                                 mock_landmarks):
        """Blink challenge must still complete with bypass active."""
        session_id = "bypass-blink-test"
        challenge_seq = ["BLINK"]

        calib = [make_frame(yaw=0.0) for _ in range(12)]
        mock_landmarks.analyze.side_effect = calib + BLINK_SEQ

        img = np.zeros((480, 640, 3), dtype=np.uint8)
        decisions = []
        for i in range(len(calib) + len(BLINK_SEQ)):
            d, _ = liveness_service_bypass.process_frame(
                session_id, challenge_seq, i, img)
            decisions.append(d)

        assert decisions[-1] == Decision.PASSED

    def test_turn_challenge_passes_with_bypass(self, liveness_service_bypass,
                                                mock_landmarks):
        """Head-turn challenge must still complete with bypass active."""
        session_id = "bypass-turn-test"
        challenge_seq = ["TURN_LEFT"]

        calib = [make_frame(yaw=0.0) for _ in range(12)]
        mock_landmarks.analyze.side_effect = calib + TURN_LEFT_SEQ

        img = np.zeros((480, 640, 3), dtype=np.uint8)
        for i in range(len(calib) + len(TURN_LEFT_SEQ)):
            d, _ = liveness_service_bypass.process_frame(
                session_id, challenge_seq, i, img)

        assert d == Decision.PASSED

    def test_challenges_not_auto_passed_with_bypass(self, liveness_service_bypass,
                                                     mock_landmarks):
        """Without performing the actual blink, the session must NOT pass."""
        session_id = "bypass-noaction-test"
        challenge_seq = ["BLINK"]

        # 20 frames of neutral pose — no blink, no head turn
        mock_landmarks.analyze.side_effect = [make_frame(yaw=0.0) for _ in range(20)]
        img = np.zeros((480, 640, 3), dtype=np.uint8)

        for i in range(20):
            d, _ = liveness_service_bypass.process_frame(
                session_id, challenge_seq, i, img)

        assert d != Decision.PASSED, "Challenges must not auto-pass without action"

    def test_multi_challenge_order_with_bypass(self, liveness_service_bypass,
                                                  mock_landmarks):
        """Multiple challenges must still pass in order with bypass active."""
        session_id = "bypass-order-test"
        challenge_seq = ["TURN_LEFT", "BLINK"]

        calib = [make_frame(yaw=0.0) for _ in range(12)]
        all_frames = calib + TURN_LEFT_SEQ + BLINK_SEQ
        mock_landmarks.analyze.side_effect = all_frames

        img = np.zeros((480, 640, 3), dtype=np.uint8)
        for i in range(len(all_frames)):
            d, _ = liveness_service_bypass.process_frame(
                session_id, challenge_seq, i, img)

        assert d == Decision.PASSED
