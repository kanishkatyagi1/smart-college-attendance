"""YuNet face detection + SFace embedding extraction.

Adapted from the prototype's ``face_module/recognition.py``.  Uses the current
tested YuNet thresholds (score 0.6, NMS 0.3) and adds face-count diagnostics
from the production code.  Detection is thread-safe via a lock because
``FaceDetectorYN.setInputSize`` mutates shared state.

SFace returns a 128-dimensional embedding that Spring Boot matches against
AES-256-GCM encrypted vectors in MySQL.  Flask never stores embeddings.
"""
from __future__ import annotations

import logging
import os
from threading import Lock

import cv2
import numpy as np

from .config import Config

_log = logging.getLogger(__name__)

YUNET_SCORE_INDEX = 14  # confidence is the 15th element in a YuNet row


class FaceError(ValueError):
    """A fail-closed detector rejection with numeric local diagnostics."""


class FaceDetectorError(FaceError):
    """A recoverable YuNet inference failure that must never imply verification."""


class FaceDetectionError(FaceError):
    """A fail-closed detector rejection carrying safe numeric diagnostics."""

    def __init__(self, message: str, reason: str, faces, diagnostic_image_written: bool = False):
        super().__init__(message)
        self.reason = reason
        self.diagnostics = _detection_diagnostics(faces)
        self.diagnostic_image_written = diagnostic_image_written


def _box_iou(first, second) -> float:
    ax1, ay1, aw, ah = (float(v) for v in first[:4])
    bx1, by1, bw, bh = (float(v) for v in second[:4])
    ax2, ay2 = ax1 + max(0.0, aw), ay1 + max(0.0, ah)
    bx2, by2 = bx1 + max(0.0, bw), by1 + max(0.0, bh)
    inter_w = max(0.0, min(ax2, bx2) - max(ax1, bx1))
    inter_h = max(0.0, min(ay2, by2) - max(ay1, by1))
    intersection = inter_w * inter_h
    union = max(0.0, aw) * max(0.0, ah) + max(0.0, bw) * max(0.0, bh) - intersection
    return intersection / union if union else 0.0


def _detection_diagnostics(faces) -> dict:
    rows = [] if faces is None else faces
    detections = [{
        "index": i,
        "x": round(float(f[0]), 2),
        "y": round(float(f[1]), 2),
        "width": round(float(f[2]), 2),
        "height": round(float(f[3]), 2),
        "score": round(float(f[YUNET_SCORE_INDEX]), 6),
    } for i, f in enumerate(rows)]
    overlaps = []
    for fi in range(len(rows)):
        for si in range(fi + 1, len(rows)):
            iou = _box_iou(rows[fi], rows[si])
            overlaps.append({
                "first": fi, "second": si, "iou": round(iou, 6),
                "duplicate_candidate": iou >= 0.85,
            })
    return {"count": len(rows), "detections": detections, "overlaps": overlaps}


class FaceEngine:
    """YuNet detector + SFace embedding model; both are CPU OpenCV ONNX models."""

    def __init__(self, config: Config):
        self.config = config
        self.detector = None
        self.recognizer = None
        self._detector_lock = Lock()
        self.unavailable_reason: str | None = None

        if not config.yunet_model.exists() or not config.sface_model.exists():
            self.unavailable_reason = "Recognition models not found."
            return
        try:
            self.detector = cv2.FaceDetectorYN.create(
                str(config.yunet_model), "", (320, 320),
                score_threshold=config.yunet_score_threshold,
                nms_threshold=config.yunet_nms_threshold,
            )
            self.recognizer = cv2.FaceRecognizerSF.create(str(config.sface_model), "")
        except cv2.error as error:
            self.unavailable_reason = f"Recognition model could not be loaded: {error}"

    @property
    def is_available(self) -> bool:
        return self.detector is not None and self.recognizer is not None

    # -- detection ----------------------------------------------------------

    def detect_faces(self, image: np.ndarray):
        """Return the raw YuNet faces array (may be None)."""
        if image is None or image.dtype != np.uint8 or image.ndim != 3 or image.shape[2] != 3:
            raise FaceError("Invalid image format.")
        image = np.ascontiguousarray(image)
        h, w = image.shape[:2]
        with self._detector_lock:
            self.detector.setInputSize((w, h))
            _, faces = self.detector.detect(image)
        _log_detection(w, h, faces)
        return faces

    def detect_single_face_with_box(self, image: np.ndarray) -> np.ndarray:
        """Detect exactly one face; return the YuNet row (bbox + landmarks + score).

        Raises ``FaceDetectionError`` with zero/multiple-face diagnostics.
        """
        faces = self.detect_faces(image)
        count = 0 if faces is None else len(faces)
        if count == 1:
            return faces[0]
        if count == 0:
            raise FaceDetectionError("No face detected", "NO_FACE", faces)
        raise FaceDetectionError(
            f"Expected exactly one face, found {count}", "MULTIPLE_FACES", faces
        )

    # -- embedding ----------------------------------------------------------

    def extract_embedding(self, image: np.ndarray, face_info: np.ndarray) -> np.ndarray:
        """Align-crop with SFace landmarks and return a 128-dim float32 vector."""
        aligned = self.recognizer.alignCrop(image, face_info)
        features = np.asarray(self.recognizer.feature(aligned))
        if features.ndim != 2 or features.shape[0] != 1:
            raise FaceError("SFace returned an invalid embedding tensor shape")
        embedding = features[0].astype(np.float64)
        if embedding.size != 128:
            raise FaceError(f"SFace returned {embedding.size} values; expected 128")
        if not np.isfinite(embedding).all():
            raise FaceError("Embedding contains non-finite values")
        return embedding


def _log_detection(w: int, h: int, faces) -> None:
    n = 0 if faces is None else len(faces)
    _log.info("[DIAG] YuNet: image %dx%d detected_faces=%d", w, h, n)
    for i in range(n):
        f = faces[i]
        _log.info("[DIAG] YuNet face[%d]: x=%.1f y=%.1f w=%.1f h=%.1f score=%.3f",
                  i, float(f[0]), float(f[1]), float(f[2]), float(f[3]),
                  float(f[YUNET_SCORE_INDEX]))
