"""MediaPipe face analysis: blendshapes plus landmark-derived pose.

Adapted from the prototype's ``face_module/landmarks.py``.  The key change for
the integrated multi-session service is **IMAGE mode** instead of VIDEO mode.

VIDEO mode maintains internal tracking state between ``detect_for_video``
calls.  When two sessions interleave through the shared landmarker, that
tracking state leaks from one session's face to the other's.  A global
timestamp lock serialises calls but does **not** prevent this — the internal
filter persists between locked calls.

IMAGE mode (``detect``) is stateless: each call is independent, so no
tracking state can leak between sessions.  The Lock is retained because
MediaPipe's ``detect`` is not documented as thread-safe.

Per-session temporal smoothing (blink, head-turn stability) is handled by
``LivenessService``, not by MediaPipe's internal tracker.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from threading import Lock

import cv2
import numpy as np

_log = logging.getLogger(__name__)


@dataclass
class LandmarkFrame:
    """Per-frame analysis result from MediaPipe."""
    blink_left: float
    blink_right: float
    yaw: float
    pitch: float
    roll: float
    glasses_score: float = 0.0
    eye_region_quality: float = 0.0
    bridge_region_score: float = 0.0


class LandmarkAnalyzer:
    """MediaPipe Face Landmarker in IMAGE mode (stateless, multi-session safe).

    A single shared instance is safe for concurrent sessions because IMAGE
    mode does not maintain tracking state between calls.  The Lock serialises
    calls because MediaPipe's ``detect`` is not documented as thread-safe.

    Lifecycle
    ---------
    Call ``close()`` explicitly when the service shuts down.  This avoids the
    ``TypeError: 'NoneType' object is not callable`` that MediaPipe 1.2.x
    raises inside ``FaceLandmarker.__del__`` when Python interpreter teardown
    has already set some C-extension callbacks to None before the GC runs the
    finaliser.  The class also supports the context-manager protocol::

        with LandmarkAnalyzer(model_path) as la:
            ...
    """

    def __init__(self, model_path):
        self.landmarker = None
        self.reason: str | None = None
        self._lock = Lock()
        self._closed = False
        try:
            import mediapipe as mp
            from mediapipe.tasks import python
            from mediapipe.tasks.python import vision
            if not model_path.exists():
                raise RuntimeError("MediaPipe Face Landmarker model is missing.")
            options = vision.FaceLandmarkerOptions(
                base_options=python.BaseOptions(model_asset_path=str(model_path)),
                running_mode=vision.RunningMode.IMAGE,
                num_faces=1,
                output_face_blendshapes=True,
                output_facial_transformation_matrixes=True,
            )
            self._mp = mp
            self.landmarker = vision.FaceLandmarker.create_from_options(options)
        except Exception as error:
            self.reason = str(error)
            _log.warning("MediaPipe LandmarkAnalyzer unavailable: %s", error)

    def close(self) -> None:
        """Deterministically release the native MediaPipe resources.

        Safe to call multiple times.  After ``close()``, ``available`` returns
        False and ``analyze()`` raises RuntimeError.
        """
        with self._lock:
            if self._closed:
                return
            self._closed = True
            lm = self.landmarker
            self.landmarker = None
        if lm is not None:
            try:
                lm.close()
            except Exception as exc:  # pragma: no cover — mediapipe internals
                _log.debug("Error closing FaceLandmarker (non-fatal): %s", exc)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    @property
    def available(self) -> bool:
        return self.landmarker is not None and not self._closed

    def analyze(self, image: np.ndarray):
        """Analyze a single BGR frame; return ``LandmarkFrame`` or raise.

        IMAGE mode: each call is independent — no tracking state is maintained
        between calls, so concurrent sessions cannot leak state.  Raises
        ``RuntimeError`` if no face is found or MediaPipe is unavailable.
        """
        if not self.available:
            raise RuntimeError(self.reason or "MediaPipe is unavailable.")
        with self._lock:
            if not self.available:
                raise RuntimeError("LandmarkAnalyzer has been closed.")
            rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
            result = self.landmarker.detect(
                self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=rgb),
            )
        if len(result.face_landmarks) != 1:
            raise RuntimeError("Landmarks unavailable; look directly at the camera.")
        shapes = {entry.category_name: entry.score for entry in result.face_blendshapes[0]}
        points = result.face_landmarks[0]
        h, w = image.shape[:2]
        # solvePnP: stable landmark positions -> yaw/pitch/roll degrees.
        ids = [1, 152, 33, 263, 61, 291]
        image_points = np.array([(points[i].x * w, points[i].y * h) for i in ids], dtype=np.float64)
        model_points = np.array([
            (0, 0, 0), (0, -63, -12), (-43, 32, -26), (43, 32, -26), (-28, -28, -24), (28, -28, -24),
        ], dtype=np.float64)
        focal = float(w)
        matrix = np.array([[focal, 0, w / 2], [0, focal, h / 2], [0, 0, 1]], dtype=np.float64)
        ok, rotation, _ = cv2.solvePnP(
            model_points, image_points, matrix, np.zeros((4, 1)), flags=cv2.SOLVEPNP_ITERATIVE
        )
        if not ok:
            raise RuntimeError("Unable to estimate head pose.")
        angles, *_ = cv2.RQDecomp3x3(cv2.Rodrigues(rotation)[0])
        glasses = self._glasses_metrics(image, points)
        return LandmarkFrame(
            float(shapes.get("eyeBlinkLeft", 0)),
            float(shapes.get("eyeBlinkRight", 0)),
            float(angles[1]),   # yaw
            float(angles[0]),   # pitch
            float(angles[2]),   # roll
            **glasses,
        )

    @staticmethod
    def _glasses_metrics(image, points):
        """Conservative frame evidence from landmark-bounded eye/bridge regions."""
        height, width = image.shape[:2]
        left_ids = (33, 133, 159, 145, 160, 144, 153, 154, 155)
        right_ids = (263, 362, 386, 374, 387, 373, 380, 381, 382)

        def box(ids):
            coords = np.array([(points[i].x * width, points[i].y * height) for i in ids])
            pad = max(4, int(max(np.ptp(coords[:, 0]), np.ptp(coords[:, 1])) * 0.45))
            x1, y1 = np.maximum(coords.min(axis=0).astype(int) - pad, 0)
            x2, y2 = np.minimum(coords.max(axis=0).astype(int) + pad, (width - 1, height - 1))
            return int(x1), int(y1), int(x2), int(y2)

        left, right = box(left_ids), box(right_ids)
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

        def density(region):
            x1, y1, x2, y2 = region
            crop = gray[y1:y2 + 1, x1:x2 + 1]
            return 0.0 if crop.size == 0 else float(np.mean(cv2.Canny(crop, 60, 140) > 0))

        left_d, right_d = density(left), density(right)
        bridge_box = (min(left[2], right[2]), min(left[1], right[1]),
                      max(left[0], right[0]), max(left[3], right[3]))
        bridge_d = density(bridge_box)
        eye_d = (left_d + right_d) / 2
        symmetry = max(0.0, 1.0 - abs(left_d - right_d) / max(eye_d, 0.001))
        eye_evidence = np.clip((eye_d - 0.13) / 0.16, 0, 1)
        bridge_evidence = np.clip((bridge_d - 0.09) / 0.16, 0, 1)
        bridge_support = np.clip((bridge_d - 0.075) / 0.075, 0, 1)
        glasses_score = (0.35 * eye_evidence + 0.65 * bridge_evidence) * (0.4 + 0.6 * bridge_support) * symmetry
        return {
            "glasses_score": float(glasses_score),
            "eye_region_quality": float(eye_evidence * symmetry),
            "bridge_region_score": float(bridge_evidence),
        }
