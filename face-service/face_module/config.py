"""Configuration for the face-service core package.

Adapted from the prototype's ``face_module/config.py``.  All storage-related
fields (``data_dir``, ``face_match_threshold``, ``sample_count``) are removed
because storage is owned by Spring Boot, not Flask.  Only model paths and
liveness/anti-spoof tuning remain.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import os


def _is_dev_environment() -> bool:
    """Return True only when Flask is explicitly running in development mode.

    Checks FLASK_ENV=development OR FLASK_DEBUG=1/true.  Any other value —
    including production, staging, test, or an unset variable — returns False.
    This prevents DISABLE_PASSIVE_ANTISPOOF from being activated in production
    even if the env var is accidentally set there.
    """
    flask_env = os.getenv("FLASK_ENV", "").lower()
    flask_debug = os.getenv("FLASK_DEBUG", "").lower()
    return flask_env == "development" or flask_debug in ("1", "true")


def _resolve_disable_passive_antispoof() -> bool:
    """Resolve the DISABLE_PASSIVE_ANTISPOOF flag with the dev-only guard.

    Returns True only when ALL of these hold:
      1. FLASK_ENV=development (or FLASK_DEBUG=1/true) — dev mode is confirmed.
      2. DISABLE_PASSIVE_ANTISPOOF=true is explicitly set.

    In production (FLASK_ENV != development) the flag is always False regardless
    of what DISABLE_PASSIVE_ANTISPOOF is set to.
    """
    if not _is_dev_environment():
        return False
    return os.getenv("DISABLE_PASSIVE_ANTISPOOF", "false").lower() == "true"


@dataclass(frozen=True)
class Config:
    """Immutable service configuration resolved from the environment."""

    models_dir: Path
    yunet_model: Path
    sface_model: Path
    landmark_model: Path
    antispoof_v1se_model: Path   # MiniFASNetV1SE (4.0 crop scale)
    antispoof_v2_model: Path      # MiniFASNetV2   (2.7 crop scale)

    # YuNet detection thresholds (match the current tested values).
    yunet_score_threshold: float
    yunet_nms_threshold: float

    # Passive anti-spoofing.
    liveness_threshold: float
    anti_spoof_window: int
    blur_min_variance: float
    min_brightness: float
    max_brightness: float
    min_face_ratio: float

    # Active liveness.
    calibration_frames: int
    challenge_timeout_seconds: float
    max_liveness_sessions: int
    liveness_session_timeout_seconds: float
    terminal_retention_seconds: int
    max_frames_per_session: int
    max_retries: int

    debug_liveness: bool

    # Development-only: skip MiniFASNet passive anti-spoof inference.
    # Allowed ONLY when FLASK_ENV=development (or FLASK_DEBUG=1 / FLASK_DEBUG=true).
    # Production deployments ignore DISABLE_PASSIVE_ANTISPOOF even if set.
    # Quality checks (blur, brightness, face ratio) still run when this is True.
    # Active liveness challenges and SFace identity matching are unaffected.
    disable_passive_antispoof: bool

    @classmethod
    def from_environment(cls, models_dir: Path | None = None) -> "Config":
        if models_dir is None:
            models_dir = Path(__file__).resolve().parent.parent / "models"
        return cls(
            models_dir=models_dir,
            yunet_model=models_dir / "face_detection_yunet_2023mar.onnx",
            sface_model=models_dir / "face_recognition_sface_2021dec.onnx",
            landmark_model=models_dir / "face_landmarker.task",
            antispoof_v1se_model=models_dir / "4_0_0_80x80_MiniFASNetV1SE.onnx",
            antispoof_v2_model=models_dir / "2.7_80x80_MiniFASNetV2.onnx",
            yunet_score_threshold=float(os.getenv("YUNET_SCORE_THRESHOLD", "0.6")),
            yunet_nms_threshold=float(os.getenv("YUNET_NMS_THRESHOLD", "0.3")),
            liveness_threshold=float(os.getenv("FACE_LIVENESS_THRESHOLD", "0.65")),
            anti_spoof_window=int(os.getenv("ANTI_SPOOF_WINDOW", "5")),
            blur_min_variance=float(os.getenv("FACE_QUALITY_BLUR_MIN_VARIANCE", "50.0")),
            min_brightness=float(os.getenv("FACE_QUALITY_MIN_BRIGHTNESS", "15.0")),
            max_brightness=float(os.getenv("FACE_QUALITY_MAX_BRIGHTNESS", "245.0")),
            min_face_ratio=float(os.getenv("FACE_QUALITY_MIN_FACE_RATIO", "0.03")),
            calibration_frames=int(os.getenv("LIVENESS_CALIBRATION_FRAMES", "12")),
            challenge_timeout_seconds=float(os.getenv("LIVENESS_CHALLENGE_TIMEOUT", "6")),
            max_liveness_sessions=int(os.getenv("MAX_LIVENESS_SESSIONS", "1000")),
            liveness_session_timeout_seconds=float(os.getenv("LIVENESS_SESSION_TIMEOUT", "30")),
            terminal_retention_seconds=int(os.getenv("LIVENESS_TERMINAL_RETENTION", "60")),
            max_frames_per_session=int(os.getenv("LIVENESS_MAX_FRAMES", "30")),
            max_retries=int(os.getenv("LIVENESS_MAX_RETRIES", "1")),
            debug_liveness=os.getenv("DEBUG_LIVENESS", "false").lower() == "true",
            disable_passive_antispoof=_resolve_disable_passive_antispoof(),
        )
