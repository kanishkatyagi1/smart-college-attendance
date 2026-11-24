"""Backward-compatible pipeline wrapper.

The real logic lives in ``face_module/recognition.py`` and
``face_module/antispoof.py``.  This module preserves the
``FaceVerificationPipeline`` API used by ``app.py`` and legacy tests.
"""
from dataclasses import dataclass
from typing import Any, Dict, Optional

import numpy as np

from face_module.config import Config
from face_module.recognition import FaceEngine, FaceDetectionError, FaceError
from face_module.antispoof import AntiSpoofEngine

EXPECTED_EMBEDDING_DIM = 128

_config = Config.from_environment()
_engine = FaceEngine(_config)
_anti_spoof = AntiSpoofEngine(_config)


class FaceDetector:
    def detect(self, image: np.ndarray) -> np.ndarray:
        return _engine.detect_single_face_with_box(image)


class LivenessDetector:
    def assess(self, image: np.ndarray, bbox: np.ndarray) -> Dict[str, Any]:
        return _anti_spoof.assess(image, bbox)


class FaceAligner:
    def align(self, image: np.ndarray, face_info: np.ndarray) -> np.ndarray:
        return _engine.recognizer.alignCrop(image, face_info)


class FaceEmbeddingExtractor:
    def extract(self, aligned_face: np.ndarray) -> np.ndarray:
        features = np.asarray(_engine.recognizer.feature(aligned_face))
        if features.ndim != 2 or features.shape[0] != 1:
            raise ValueError("SFace returned an invalid embedding tensor shape")
        embedding = features[0].astype(float)
        if embedding.size != EXPECTED_EMBEDDING_DIM:
            raise ValueError(f"SFace returned {embedding.size} values; expected {EXPECTED_EMBEDDING_DIM}")
        if not np.isfinite(embedding).all():
            raise ValueError("Embedding contains non-finite values")
        return embedding


@dataclass(frozen=True)
class FaceProcessingResult:
    face_info: np.ndarray
    liveness: Dict[str, Any]
    embedding: Optional[np.ndarray]


class FaceVerificationPipeline:
    def __init__(self, detector=None, liveness_detector=None, aligner=None, embedding_extractor=None):
        self.detector = detector or FaceDetector()
        self.liveness_detector = liveness_detector or LivenessDetector()
        self.aligner = aligner or FaceAligner()
        self.embedding_extractor = embedding_extractor or FaceEmbeddingExtractor()

    def process(self, image: np.ndarray) -> FaceProcessingResult:
        face_info = self.detector.detect(image)
        return self.process_detected(image, face_info)

    def process_detected(self, image: np.ndarray, face_info: np.ndarray) -> FaceProcessingResult:
        liveness = self.liveness_detector.assess(image, face_info[:4])
        if not liveness.get("is_real"):
            return FaceProcessingResult(face_info, liveness, None)
        aligned = self.aligner.align(image, face_info)
        embedding = self.embedding_extractor.extract(aligned)
        return FaceProcessingResult(face_info, liveness, embedding)
