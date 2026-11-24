"""Backward-compatible re-exports from ``face_module.recognition``.

The real implementation lives in ``face_module/recognition.py``.  This thin
module preserves the flat import paths (``YUNET_NET``, ``SFACE_NET``,
``detect_single_face_with_box``, etc.) used by scripts and legacy tests.
"""
import cv2
import numpy as np

from face_module.config import Config
from face_module.recognition import (
    FaceEngine,
    FaceError,
    FaceDetectionError,
    YUNET_SCORE_INDEX,
    _detection_diagnostics as detection_diagnostics,
)

_config = Config.from_environment()
_engine = FaceEngine(_config)

YUNET_NET = _engine.detector
SFACE_NET = _engine.recognizer
YUNET_SCORE_THRESHOLD = _config.yunet_score_threshold
YUNET_NMS_THRESHOLD = _config.yunet_nms_threshold


def detect_single_face_with_box(img):
    return _engine.detect_single_face_with_box(img)


def detect_single_face(img):
    """Return a grayscale face crop (legacy API)."""
    face = _engine.detect_single_face_with_box(img)
    h, w = img.shape[:2]
    x, y, fw, fh = (int(v) for v in face[:4])
    x, y = max(0, x), max(0, y)
    x2, y2 = min(w, x + fw), min(h, y + fh)
    face_roi = img[y:y2, x:x2]
    return cv2.cvtColor(face_roi, cv2.COLOR_BGR2GRAY)


def extract_embedding(face_gray):
    """Legacy: extract from grayscale crop (resizes to 112x112 BGR)."""
    face_bgr = cv2.cvtColor(face_gray, cv2.COLOR_GRAY2BGR)
    resized = cv2.resize(face_bgr, (112, 112))
    emb = _engine.recognizer.feature(resized)
    emb_vec = emb[0].astype(float)
    if not np.isfinite(emb_vec).all():
        raise ValueError('Embedding contains non-finite values')
    return emb_vec.tolist()


def extract_embedding_from_image(img):
    """Legacy: detect one face and return its embedding via alignCrop."""
    face = _engine.detect_single_face_with_box(img)
    aligned = _engine.recognizer.alignCrop(img, face)
    emb = _engine.recognizer.feature(aligned)
    emb_vec = emb[0].astype(float)
    if not np.isfinite(emb_vec).all():
        raise ValueError('Embedding contains non-finite values')
    return emb_vec.tolist()
