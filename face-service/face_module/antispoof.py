"""Passive anti-spoofing using MiniFASNetV2 + MiniFASNetV1SE ensemble.

Adapted from the prototype's ``face_module/antispoof.py`` class structure, but
with the **NHWC preprocessing** from the current production code (not the NCHW
``np.transpose`` in the archive — see Phase 6 of the replacement task).

Model provenance and preprocessing contract (verified line-by-line):
  - Both ONNX graphs contain a leading Transpose(perm=[0,3,1,2]) that converts
    NHWC -> NCHW internally, so the **external** contract is NHWC [1,80,80,3].
  - Softmax is baked into both ONNX graphs; do NOT apply it externally.
  - Input: BGR float32 [0,255], NHWC, no normalization (no Div/Sub before first Conv).
  - MiniFASNetV2 cropped at scale 2.7; MiniFASNetV1SE at scale 4.0.
  - Ensemble: sum class-probability vectors, take argmax of the sum.
  - Output classes: [print_attack=0, real_face=1, replay_attack=2].
  - Liveness score = ensemble[1] / 2 (averaged real-face probability).
  - Gate: class-1 must BOTH win argmax AND exceed LIVENESS_THRESHOLD (0.65).

Quality checks (from the current production code):
  - Blur: Laplacian variance on the face region normalised to 100x100 (threshold 50.0).
  - Brightness: mean of the face grayscale region (15.0–245.0).
  - Face ratio: face area / image area (minimum 0.03).

SHA-256 model integrity is verified at load time.
"""
from __future__ import annotations

import hashlib
import logging
from typing import Any, Dict, Optional, Tuple, Union

import cv2
import numpy as np
import onnxruntime as ort

from .config import Config

_log = logging.getLogger(__name__)

# Model SHA-256 hashes (verified working in production).
_V2_SHA256 = "2e0b0bdcdaf2ed0e8ceae7a8588a8b029c0f1bf2b15876eee942b68ce51bbb29"
_V1SE_SHA256 = "1e2645be6e42759714f19ee23641366cf3d86cba5f169964880894a1fc188275"

# Blur measurement normalisation size.
_BLUR_NORM_SIZE = 100


def _verify_model_file(filepath, expected_hash: str) -> None:
    if not filepath.exists():
        raise FileNotFoundError(f"Anti-spoofing model not found: {filepath}")
    sha256 = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            sha256.update(chunk)
    actual = sha256.hexdigest().lower()
    if actual != expected_hash.lower():
        raise ValueError(f"Model integrity check failed for {filepath.name}: expected {expected_hash}, got {actual}")


def _get_new_box(src_w: int, src_h: int, bbox, scale: float = 2.7) -> Tuple[int, int, int, int]:
    """Port of upstream Minivision Silent-Face-Anti-Spoofing _get_new_box."""
    x, y, box_w, box_h = bbox[:4]
    scale = min((src_h - 1) / box_h, (src_w - 1) / box_w, scale)
    new_w = box_w * scale
    new_h = box_h * scale
    cx, cy = box_w / 2.0 + x, box_h / 2.0 + y
    ltx, lty = cx - new_w / 2.0, cy - new_h / 2.0
    rbx, rby = cx + new_w / 2.0, cy + new_h / 2.0
    if ltx < 0:
        rbx -= ltx; ltx = 0
    if lty < 0:
        rby -= lty; lty = 0
    if rbx > src_w - 1:
        ltx -= rbx - src_w + 1; rbx = src_w - 1
    if rby > src_h - 1:
        lty -= rby - src_h + 1; rby = src_h - 1
    return int(max(0, ltx)), int(max(0, lty)), int(min(src_w - 1, rbx)), int(min(src_h - 1, rby))


def _crop_face(img: np.ndarray, bbox, scale: float, out_w: int = 80, out_h: int = 80) -> np.ndarray:
    src_h, src_w = img.shape[:2]
    ltx, lty, rbx, rby = _get_new_box(src_w, src_h, bbox, scale)
    crop = img[lty: rby + 1, ltx: rbx + 1]
    return cv2.resize(crop, (out_w, out_h))


def _face_gray_region(gray: np.ndarray, bbox) -> np.ndarray:
    h, w = gray.shape[:2]
    x, y, bw, bh = (int(v) for v in bbox[:4])
    x1, y1 = max(0, min(w, x)), max(0, min(h, y))
    x2, y2 = max(0, min(w, x + bw)), max(0, min(h, y + bh))
    if x2 <= x1 or y2 <= y1:
        return gray
    return gray[y1:y2, x1:x2]


def _normalized_laplacian_var(gray_region: np.ndarray) -> float:
    if gray_region.size == 0:
        return 0.0
    h, w = gray_region.shape[:2]
    if h < 2 or w < 2:
        return 0.0
    interp = cv2.INTER_AREA if h > _BLUR_NORM_SIZE or w > _BLUR_NORM_SIZE else cv2.INTER_LINEAR
    norm = cv2.resize(gray_region, (_BLUR_NORM_SIZE, _BLUR_NORM_SIZE), interpolation=interp)
    return float(cv2.Laplacian(norm, cv2.CV_64F).var())


def _quality_metrics(img: np.ndarray, bbox) -> Dict[str, float]:
    """Face-focused quality: blur (normalised), brightness, face ratio."""
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img
    h, w = img.shape[:2]
    face_gray = _face_gray_region(gray, bbox)
    laplacian_var = _normalized_laplacian_var(face_gray)
    brightness_mean = float(np.mean(face_gray)) if face_gray.size > 0 else 0.0
    fw, fh = float(bbox[2]), float(bbox[3])
    img_area = float(h * w)
    face_ratio = float(fw * fh) / img_area if img_area > 0 else 0.0
    return {
        "laplacian_var": laplacian_var,
        "laplacian_var_raw": float(cv2.Laplacian(face_gray, cv2.CV_64F).var()),
        "laplacian_var_whole": float(cv2.Laplacian(gray, cv2.CV_64F).var()),
        "brightness_mean": brightness_mean,
        "face_ratio": face_ratio,
    }


def _decide_quality(metrics: Dict[str, float], config: Config) -> Optional[str]:
    if metrics["laplacian_var"] < config.blur_min_variance:
        return "BLURRY_IMAGE"
    if metrics["brightness_mean"] < config.min_brightness:
        return "UNDEREXPOSED_IMAGE"
    if metrics["brightness_mean"] > config.max_brightness:
        return "OVEREXPOSED_IMAGE"
    if metrics["face_ratio"] < config.min_face_ratio:
        return "FACE_TOO_SMALL"
    return None


class AntiSpoofEngine:
    """CPU MiniFASNet ensemble.  Missing models fail closed: no identity matching."""

    def __init__(self, config: Config):
        self.config = config
        self.sessions: list = []
        self.reason: str | None = None
        try:
            for path, _sha, _scale in (
                (config.antispoof_v2_model, _V2_SHA256, 2.7),
                (config.antispoof_v1se_model, _V1SE_SHA256, 4.0),
            ):
                _verify_model_file(path, _sha)
                opts = ort.SessionOptions()
                opts.log_severity_level = 3
                sess = ort.InferenceSession(str(path), sess_options=opts,
                                             providers=["CPUExecutionProvider"])
                # Validate input shape: must be NHWC [batch, 80, 80, 3].
                in_shape = sess.get_inputs()[0].shape
                if len(in_shape) != 4 or in_shape[1:4] != [80, 80, 3]:
                    raise ValueError(f"Unexpected ONNX input shape: {in_shape}, expected [N,80,80,3] (NHWC)")
                out_shape = sess.get_outputs()[0].shape
                if len(out_shape) != 2 or out_shape[1] != 3:
                    raise ValueError(f"Unexpected ONNX output shape: {out_shape}, expected [N,3]")
                # Smoke-test with NHWC zeros.
                sess.run(None, {sess.get_inputs()[0].name: np.zeros((1, 80, 80, 3), dtype=np.float32)})
                self.sessions.append(sess)
        except Exception as error:
            self.reason = str(error)
            self.sessions = []

    @property
    def available(self) -> bool:
        return len(self.sessions) == 2

    def check_quality(self, img: np.ndarray, bbox) -> Optional[str]:
        """Return a rejection reason string, or None if quality passes."""
        metrics = _quality_metrics(img, bbox)
        return _decide_quality(metrics, self.config)

    def quality_metrics(self, img: np.ndarray, bbox) -> Dict[str, float]:
        return _quality_metrics(img, bbox)

    def score(self, image: np.ndarray, face) -> float:
        """Return the ensemble real-face score (class-1 avg).

        Preprocessing: **NHWC** — crop at per-model scale, resize to 80x80,
        expand to [1,80,80,3] float32 [0,255].  Do NOT transpose to NCHW;
        the ONNX graphs handle that internally.
        """
        if not self.available:
            raise RuntimeError(self.reason or "Anti-spoof models unavailable.")
        bbox = [float(v) for v in face[:4]]
        values = []
        for session, scale in zip(self.sessions, (2.7, 4.0)):
            crop = _crop_face(image, bbox, scale=scale, out_w=80, out_h=80)
            # NHWC: [1, 80, 80, 3] float32 [0, 255] — NOT transposed.
            blob = np.expand_dims(crop, axis=0).astype(np.float32)
            logits = session.run(None, {session.get_inputs()[0].name: blob})[0][0]
            # Softmax is baked into the ONNX graph; logits are already probabilities.
            values.append(float(logits[1]))  # class 1 = real face
        return float(np.mean(values))

    def assess(self, image: np.ndarray, bbox) -> Dict[str, Any]:
        """Full passive anti-spoof assessment: quality gate + ensemble inference.

        Returns a dict with ``is_real``, ``liveness_score``, ``rejection_reason``,
        and diagnostic keys prefixed with ``_diag_`` (never forwarded to callers).
        """
        # Quality gate first (measured on detected face region).
        metrics = _quality_metrics(image, bbox)
        quality_failure = _decide_quality(metrics, self.config)
        if quality_failure is not None:
            return {
                "is_real": False,
                "liveness_score": 0.0,
                "rejection_reason": quality_failure,
                "_diag_quality": {
                    "laplacian_var": round(metrics["laplacian_var"], 3),
                    "brightness_mean": round(metrics["brightness_mean"], 3),
                    "face_ratio": round(metrics["face_ratio"], 5),
                    "quality_result": quality_failure,
                },
            }

        # Ensemble inference with NHWC preprocessing.
        bbox_f = [float(v) for v in bbox[:4]]
        preds = []
        for session, scale in zip(self.sessions, (2.7, 4.0)):
            crop = _crop_face(image, bbox_f, scale=scale)
            blob = np.expand_dims(crop, axis=0).astype(np.float32)  # NHWC
            pred = session.run(None, {session.get_inputs()[0].name: blob})[0][0]
            preds.append(pred)

        ensemble = preds[0] + preds[1]
        winning_class = int(np.argmax(ensemble))
        real_face_score = float(ensemble[1]) / 2.0
        is_real = (winning_class == 1) and (real_face_score >= self.config.liveness_threshold)

        return {
            "is_real": is_real,
            "liveness_score": round(real_face_score, 4),
            "rejection_reason": None if is_real else "SPOOF_DETECTED",
            "_diag_probabilities": ensemble,
            "_diag_m1_probabilities": preds[0],
            "_diag_m2_probabilities": preds[1],
            "_diag_quality": {
                "laplacian_var": round(metrics["laplacian_var"], 3),
                "brightness_mean": round(metrics["brightness_mean"], 3),
                "face_ratio": round(metrics["face_ratio"], 5),
                "quality_result": None,
            },
        }
