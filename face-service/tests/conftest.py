"""Shared test fixtures and helpers.

Tests use synthetic observations and mocked model outputs; they do not
depend on archived biometric photographs or the actual ONNX/MediaPipe models.
"""
from __future__ import annotations

import sys
import os
from pathlib import Path
from unittest.mock import MagicMock

import numpy as np
import pytest

# Ensure face-service root is importable
_face_service_root = Path(__file__).resolve().parent.parent
if str(_face_service_root) not in sys.path:
    sys.path.insert(0, str(_face_service_root))

from face_module.config import Config
from face_module.recognition import FaceEngine, FaceDetectionError, FaceError
from face_module.antispoof import AntiSpoofEngine
from face_module.landmarks import LandmarkAnalyzer, LandmarkFrame
from face_module.liveness_service import LivenessService, LivenessSession, Decision


class MutableClock:
    """A controllable monotonic clock for deterministic timeout tests."""
    def __init__(self, start: float = 0.0):
        self._t = start
    def __call__(self) -> float:
        return self._t
    def advance(self, seconds: float):
        self._t += seconds


def make_frame(blink_left=0.0, blink_right=0.0, yaw=0.0, pitch=0.0,
               roll=0.0, glasses_score=0.0, eye_region_quality=0.0,
               bridge_region_score=0.0) -> LandmarkFrame:
    """Build a LandmarkFrame with controlled values for deterministic tests."""
    return LandmarkFrame(
        blink_left=blink_left, blink_right=blink_right,
        yaw=yaw, pitch=pitch, roll=roll,
        glasses_score=glasses_score,
        eye_region_quality=eye_region_quality,
        bridge_region_score=bridge_region_score,
    )


def make_face_info(x=100, y=100, w=200, h=200, score=0.95):
    """Build a fake YuNet detection row [x, y, w, h, 10 landmarks, score]."""
    row = [float(x), float(y), float(w), float(h)]
    row.extend([0.0] * 10)  # 5 landmark pairs
    row.append(float(score))  # confidence
    return np.array(row, dtype=np.float32)


@pytest.fixture
def config():
    return Config.from_environment()


@pytest.fixture
def clock():
    return MutableClock(0.0)


@pytest.fixture
def mock_engine():
    engine = MagicMock(spec=FaceEngine)
    engine.is_available = True
    engine.detect_single_face_with_box.return_value = make_face_info()
    engine.extract_embedding.return_value = np.ones(128, dtype=np.float64)
    return engine


@pytest.fixture
def mock_anti_spoof():
    anti_spoof = MagicMock(spec=AntiSpoofEngine)
    anti_spoof.available = True
    anti_spoof.check_quality.return_value = None  # always passes
    anti_spoof.score.return_value = 0.9  # genuine
    anti_spoof.assess.return_value = {
        "is_real": True,
        "liveness_score": 0.9,
        "rejection_reason": None,
    }
    return anti_spoof


@pytest.fixture
def mock_landmarks():
    landmarks = MagicMock(spec=LandmarkAnalyzer)
    landmarks.available = True
    landmarks.analyze.return_value = make_frame()
    return landmarks


@pytest.fixture
def liveness_service(mock_engine, mock_anti_spoof, mock_landmarks, config, clock):
    """A LivenessService with fully mocked dependencies and a controllable clock."""
    return LivenessService(config, mock_engine, mock_landmarks, mock_anti_spoof,
                           clock=clock)


@pytest.fixture
def synthetic_image():
    """A small BGR image for tests that need an ndarray."""
    return np.random.randint(0, 255, (480, 640, 3), dtype=np.uint8)


def calibrate_then_challenge(liveness_service, mock_landmarks, session_id,
                            challenge_seq, challenge_frames):
    """Run 12 calibration frames (yaw=0), then the given challenge frames.

    Returns the (Decision, reason) from the last challenge frame.
    """
    calib = [make_frame(yaw=0.0) for _ in range(12)]
    all_frames = calib + challenge_frames
    mock_landmarks.analyze.side_effect = all_frames

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    total = len(all_frames)
    decision, reason = Decision.PENDING, None
    for i in range(total):
        decision, reason = liveness_service.process_frame(
            session_id, challenge_seq, i, img
        )
    return decision, reason


# ---------------------------------------------------------------------------
# MediaPipe deterministic cleanup
# ---------------------------------------------------------------------------
# MediaPipe 1.2.x raises ``RuntimeError: cannot schedule new futures after
# shutdown`` inside ``FaceLandmarker.__del__`` when Python's interpreter shuts
# down the C-extension thread pool before the GC runs object finalisers.
#
# The fix is to call ``lm.close()`` while the interpreter is still healthy.
# Our ``LandmarkAnalyzer.close()`` does that, so tests that create a
# ``LandmarkAnalyzer`` must call ``close()`` explicitly (or use it as a
# context manager).  The fixture below installs a ``finalizer`` on every
# ``LandmarkAnalyzer`` instance the test suite creates so cleanup happens
# even when test code forgets.

import gc as _gc

@pytest.fixture(autouse=True, scope="session")
def _mediapipe_cleanup():
    """Collect and close all LandmarkAnalyzer instances before process exit."""
    yield
    # Give the GC one pass to find the instances, then close any still open.
    try:
        from face_module.landmarks import LandmarkAnalyzer
        _gc.collect()
        for obj in _gc.get_objects():
            if isinstance(obj, LandmarkAnalyzer) and obj.available:
                try:
                    obj.close()
                except Exception:
                    pass
        _gc.collect()
    except Exception:
        pass
