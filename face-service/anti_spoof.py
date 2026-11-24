"""Backward-compatible re-exports from ``face_module.antispoof``.

The real implementation lives in ``face_module/antispoof.py``.  This thin
module preserves the flat import path used by scripts and legacy tests.
"""
from face_module.config import Config
from face_module.antispoof import (
    AntiSpoofEngine,
    _get_new_box as get_new_box,
    _crop_face as crop_face_for_anti_spoof,
    _quality_metrics,
    _decide_quality,
    _BLUR_NORM_SIZE,
    _V2_SHA256 as EXPECTED_SHA256,
    _V1SE_SHA256 as EXPECTED_SHA256_2,
)

_config = Config.from_environment()
_engine = AntiSpoofEngine(_config)

LIVENESS_THRESHOLD = _config.liveness_threshold
BLUR_MIN_VARIANCE = _config.blur_min_variance
MIN_BRIGHTNESS = _config.min_brightness
MAX_BRIGHTNESS = _config.max_brightness
MIN_FACE_RATIO = _config.min_face_ratio

MODEL_FILENAME = "2.7_80x80_MiniFASNetV2.onnx"
MODEL2_FILENAME = "4_0_0_80x80_MiniFASNetV1SE.onnx"

_SESSION = _engine.sessions[0] if _engine.available else None
_SESSION2 = _engine.sessions[1] if _engine.available else None


def check_image_quality(img, bbox):
    return _engine.check_quality(img, bbox)


def check_liveness(img, bbox, threshold=None):
    result = _engine.assess(img, bbox)
    return {
        "is_real": result["is_real"],
        "liveness_score": result["liveness_score"],
        "rejection_reason": result["rejection_reason"],
    }
