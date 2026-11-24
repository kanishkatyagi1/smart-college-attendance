"""Face module tests (tests 1-8): detection, embedding, NHWC, anti-spoof.

Tests use mocked model sessions and synthetic images; no biometric
photographs or real ONNX models are required.
"""
from __future__ import annotations

from threading import Lock
from unittest.mock import MagicMock

import numpy as np
import pytest

from face_module.config import Config
from face_module.recognition import FaceEngine, FaceDetectionError, FaceError
from face_module.antispoof import AntiSpoofEngine, _crop_face, _get_new_box
from tests.conftest import make_face_info


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def make_mock_engine():
    """Create a FaceEngine with mocked detector/recognizer (no model files needed)."""
    config = Config.from_environment()
    engine = FaceEngine.__new__(FaceEngine)
    engine.config = config
    engine.detector = MagicMock()
    engine.recognizer = MagicMock()
    engine._detector_lock = Lock()
    engine.unavailable_reason = None
    return engine


def make_mock_antispoof(real_probs=(0.8, 0.85)):
    """Create an AntiSpoofEngine with mocked ONNX sessions.

    ``real_probs`` sets the class-1 (real face) probability for each model.
    """
    config = Config.from_environment()
    engine = AntiSpoofEngine.__new__(AntiSpoofEngine)
    engine.config = config
    engine.reason = None

    sessions = []
    for prob in real_probs:
        sess = MagicMock()
        mock_input = MagicMock()
        mock_input.name = "input"
        sess.get_inputs.return_value = [mock_input]
        # ONNX output: [batch, 3] softmax probabilities
        other = (1.0 - prob) / 2
        sess.run.return_value = [np.array([[other, prob, other]], dtype=np.float32)]
        sessions.append(sess)
    engine.sessions = sessions
    return engine


# ---------------------------------------------------------------------------
# Test 1: YuNet one-face detection
# ---------------------------------------------------------------------------

def test_one_face_detection():
    """Test 1: YuNet detects exactly one face and returns it."""
    engine = make_mock_engine()
    face = make_face_info(x=120, y=80, w=200, h=200, score=0.92)
    engine.detector.detect.return_value = (MagicMock(), np.array([face]))

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    result = engine.detect_single_face_with_box(img)
    assert result is not None
    assert float(result[0]) == 120.0
    assert float(result[2]) == 200.0


# ---------------------------------------------------------------------------
# Test 2: No-face rejection
# ---------------------------------------------------------------------------

def test_no_face_rejection():
    """Test 2: Zero faces raises FaceDetectionError with reason NO_FACE."""
    engine = make_mock_engine()
    engine.detector.detect.return_value = (MagicMock(), None)

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    with pytest.raises(FaceDetectionError) as exc_info:
        engine.detect_single_face_with_box(img)
    assert exc_info.value.reason == "NO_FACE"


# ---------------------------------------------------------------------------
# Test 3: Multiple-face rejection
# ---------------------------------------------------------------------------

def test_multiple_face_rejection():
    """Test 3: Two faces raises FaceDetectionError with reason MULTIPLE_FACES."""
    engine = make_mock_engine()
    face1 = make_face_info(x=50, y=50, w=150, h=150)
    face2 = make_face_info(x=400, y=50, w=150, h=150)
    engine.detector.detect.return_value = (MagicMock(), np.array([face1, face2]))

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    with pytest.raises(FaceDetectionError) as exc_info:
        engine.detect_single_face_with_box(img)
    assert exc_info.value.reason == "MULTIPLE_FACES"


# ---------------------------------------------------------------------------
# Test 4: SFace embedding dimension and finite values
# ---------------------------------------------------------------------------

def test_embedding_dimension_and_finite():
    """Test 4: SFace returns a 128-dim embedding with finite values."""
    engine = make_mock_engine()
    engine.recognizer.alignCrop.return_value = np.zeros((112, 112, 3), dtype=np.uint8)
    engine.recognizer.feature.return_value = np.ones((1, 128), dtype=np.float32) * 0.5

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    face = make_face_info()
    embedding = engine.extract_embedding(img, face)
    assert embedding.shape == (128,), f"Expected (128,), got {embedding.shape}"
    assert np.isfinite(embedding).all()
    assert embedding.dtype == np.float64


def test_embedding_wrong_dimension_rejected():
    """Test 4b: Non-128-dim embedding raises FaceError."""
    engine = make_mock_engine()
    engine.recognizer.alignCrop.return_value = np.zeros((112, 112, 3), dtype=np.uint8)
    engine.recognizer.feature.return_value = np.ones((1, 64), dtype=np.float32)

    img = np.zeros((480, 640, 3), dtype=np.uint8)
    face = make_face_info()
    with pytest.raises(FaceError, match="128"):
        engine.extract_embedding(img, face)


# ---------------------------------------------------------------------------
# Test 5: MiniFASNet V1SE and V2 inference
# ---------------------------------------------------------------------------

def test_minifasnet_both_models_inference():
    """Test 5: Both MiniFASNet models run and produce 3-class probabilities."""
    engine = make_mock_antispoof(real_probs=(0.80, 0.90))
    img = np.random.randint(0, 255, (480, 640, 3), dtype=np.uint8)
    bbox = [100, 100, 200, 200]

    score = engine.score(img, make_face_info())
    # score = mean of [0.80, 0.90] = 0.85
    assert abs(score - 0.85) < 0.01

    # Verify both sessions were called
    assert engine.sessions[0].run.called
    assert engine.sessions[1].run.called


def test_minifasnet_ensemble_argmax():
    """Test 5b: Ensemble sums probabilities and takes argmax."""
    engine = make_mock_antispoof(real_probs=(0.80, 0.90))
    img = np.random.randint(0, 255, (480, 640, 3), dtype=np.uint8)
    bbox = [100, 100, 200, 200]

    result = engine.assess(img, bbox)
    # ensemble[1] = 0.80 + 0.90 = 1.70, real_face_score = 1.70/2 = 0.85 >= 0.65
    assert result["is_real"] is True
    assert abs(result["liveness_score"] - 0.85) < 0.01


# ---------------------------------------------------------------------------
# Test 6: Correct NHWC preprocessing
# ---------------------------------------------------------------------------

def test_nhwc_preprocessing_shape():
    """Test 6: Anti-spoof input is NHWC [1,80,80,3] float32 [0,255], NOT NCHW."""
    img = np.random.randint(0, 255, (480, 640, 3), dtype=np.uint8)
    bbox = [100, 100, 200, 200]

    # V2 (scale 2.7)
    crop_v2 = _crop_face(img, bbox, scale=2.7)
    blob_v2 = np.expand_dims(crop_v2, axis=0).astype(np.float32)
    assert blob_v2.shape == (1, 80, 80, 3), \
        f"Expected NHWC [1,80,80,3], got {blob_v2.shape} — NCHW would be [1,3,80,80]"
    assert blob_v2.dtype == np.float32
    assert blob_v2.min() >= 0.0
    assert blob_v2.max() <= 255.0

    # V1SE (scale 4.0)
    crop_v1se = _crop_face(img, bbox, scale=4.0)
    blob_v1se = np.expand_dims(crop_v1se, axis=0).astype(np.float32)
    assert blob_v1se.shape == (1, 80, 80, 3), \
        f"Expected NHWC [1,80,80,3], got {blob_v1se.shape}"
    assert blob_v1se.dtype == np.float32


def test_nhwc_not_nchw():
    """Test 6b: Explicitly verify the tensor is NOT NCHW [1,3,80,80]."""
    img = np.random.randint(0, 255, (480, 640, 3), dtype=np.uint8)
    crop = _crop_face(img, [100, 100, 200, 200], scale=2.7)
    blob = np.expand_dims(crop, axis=0).astype(np.float32)
    # NHWC: shape[1]=80 (height), shape[2]=80 (width), shape[3]=3 (channels)
    # NCHW: shape[1]=3 (channels), shape[2]=80, shape[3]=80
    assert blob.shape[3] == 3, "Channel dimension must be last (NHWC), not second (NCHW)"
    assert blob.shape[1] == 80, "Height must be second (NHWC)"
    assert blob.shape[2] == 80, "Width must be third (NHWC)"


# ---------------------------------------------------------------------------
# Test 7: Passive spoof rejection
# ---------------------------------------------------------------------------

def test_passive_spoof_rejection():
    """Test 7: Low real-face score is rejected as SPOOF_DETECTED."""
    engine = make_mock_antispoof(real_probs=(0.10, 0.15))  # well below 0.65
    img = np.random.randint(0, 255, (480, 640, 3), dtype=np.uint8)
    bbox = [100, 100, 200, 200]

    result = engine.assess(img, bbox)
    assert result["is_real"] is False
    assert result["rejection_reason"] == "SPOOF_DETECTED"
    assert result["liveness_score"] < 0.65


# ---------------------------------------------------------------------------
# Test 8: Genuine image liveness path
# ---------------------------------------------------------------------------

def test_genuine_liveness_path():
    """Test 8: High real-face score passes the liveness gate."""
    engine = make_mock_antispoof(real_probs=(0.80, 0.90))  # mean 0.85 >= 0.65
    img = np.random.randint(0, 255, (480, 640, 3), dtype=np.uint8)
    bbox = [100, 100, 200, 200]

    result = engine.assess(img, bbox)
    assert result["is_real"] is True
    assert result["rejection_reason"] is None
    assert result["liveness_score"] >= 0.65


# ---------------------------------------------------------------------------
# Extra: Quality gate (blur rejection)
# ---------------------------------------------------------------------------

def test_quality_blur_rejection():
    """Blurry face image is rejected before anti-spoof inference."""
    engine = make_mock_antispoof(real_probs=(0.90, 0.90))
    # Create a very smooth (blurry) image — Laplacian variance will be near 0
    img = np.full((480, 640, 3), 128, dtype=np.uint8)
    bbox = [100, 100, 200, 200]

    result = engine.assess(img, bbox)
    assert result["is_real"] is False
    assert result["rejection_reason"] == "BLURRY_IMAGE"
