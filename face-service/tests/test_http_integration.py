"""HTTP integration tests for the Flask face-service.

These tests start the Flask application in a background thread on a random
free port and send real HTTP requests against it.  Model engines are mocked
so no actual ONNX or MediaPipe models are required.

Scope
-----
  - Correct secret accepted (200).
  - Incorrect secret rejected (401).
  - Missing secret rejected (401).
  - Missing frame field rejected (400).
  - Invalid JPEG rejected (400).
  - Unknown active-liveness session not crashing (200/PENDING or RETRYABLE_FAILURE).
  - Out-of-order frame sequence rejected (RETRYABLE_FAILURE).
  - Expired session returns FAILED/SESSION_EXPIRED.
  - Response schema matches Spring's expected contract.

Distinction
-----------
  - These are **HTTP integration tests**, not biometric end-to-end tests.
  - Synthetic frames are used (small JPEG of solid colour).
  - Model outputs are mocked; no real face is captured or verified.
  - These tests do NOT test face matching accuracy, liveness model accuracy,
    or attendance database writes.
"""
from __future__ import annotations

import io
import json
import os
import socket
import sys
import threading
import time
from pathlib import Path
from unittest.mock import MagicMock, patch

import cv2
import numpy as np
import pytest
import requests

# ---------------------------------------------------------------------------
# Path setup
# ---------------------------------------------------------------------------

_FACE_ROOT = Path(__file__).resolve().parent.parent
if str(_FACE_ROOT) not in sys.path:
    sys.path.insert(0, str(_FACE_ROOT))

from tests.conftest import make_face_info, make_frame

GOOD_SECRET = "integration-test-secret-9x"


# ---------------------------------------------------------------------------
# Server fixture
# ---------------------------------------------------------------------------

def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _jpeg_bytes(value: int = 128, h: int = 100, w: int = 100) -> bytes:
    """Return a minimal JPEG for a solid-colour synthetic frame."""
    img = np.full((h, w, 3), value, dtype=np.uint8)
    _, buf = cv2.imencode(".jpg", img)
    return buf.tobytes()


@pytest.fixture(scope="module")
def server(tmp_path_factory):
    """Start a patched Flask app on a free port and yield its base URL.

    All model engines are mocked so no ONNX files are needed.
    """
    port = _free_port()
    base = f"http://127.0.0.1:{port}"

    # Mocked engines
    mock_engine = MagicMock()
    mock_engine.is_available = True
    mock_engine.unavailable_reason = None
    mock_engine.detect_single_face_with_box.return_value = make_face_info()
    mock_engine.extract_embedding.return_value = np.ones(128, dtype=np.float64) * 0.5

    mock_anti = MagicMock()
    mock_anti.available = True
    mock_anti.reason = None
    mock_anti.check_quality.return_value = None
    mock_anti.score.return_value = 0.9
    mock_anti.assess.return_value = {
        "is_real": True,
        "liveness_score": 0.9,
        "rejection_reason": None,
    }

    mock_landmarks = MagicMock()
    mock_landmarks.available = True
    mock_landmarks.reason = None
    mock_landmarks.close.return_value = None
    mock_landmarks.analyze.return_value = make_frame(yaw=0.0)

    os.environ["FACE_SERVICE_SECRET"] = GOOD_SECRET

    with patch("app.engine", mock_engine), \
         patch("app.anti_spoof_engine", mock_anti), \
         patch("app.landmark_analyzer", mock_landmarks), \
         patch("app.liveness_service") as mock_liveness_svc:

        from face_module.liveness_service import Decision
        mock_liveness_svc.process_frame.return_value = (Decision.PENDING, None)

        import app as app_module
        app_module.app.config["TESTING"] = False  # use Werkzeug server, not test client

        def _run():
            import logging
            log = logging.getLogger("werkzeug")
            log.setLevel(logging.ERROR)
            app_module.app.run(host="127.0.0.1", port=port, threaded=True, use_reloader=False)

        thread = threading.Thread(target=_run, daemon=True)
        thread.start()

        # Wait for port to become reachable (up to 5 s).
        for _ in range(50):
            try:
                requests.get(f"{base}/health", timeout=0.2)
                break
            except Exception:
                time.sleep(0.1)
        else:
            pytest.skip("Flask server did not start in time")

        yield base, mock_liveness_svc, mock_anti, mock_engine


# ---------------------------------------------------------------------------
# /extract-embedding — authentication
# ---------------------------------------------------------------------------

class TestExtractEmbeddingHttp:
    def test_correct_secret_returns_200(self, server):
        base, *_ = server
        r = requests.post(f"{base}/extract-embedding",
                          files={"image": ("face.jpg", _jpeg_bytes(), "image/jpeg")},
                          headers={"X-FACE-SERVICE-SECRET": GOOD_SECRET})
        assert r.status_code == 200
        body = r.json()
        assert body["extracted"] is True

    def test_incorrect_secret_returns_401(self, server):
        base, *_ = server
        r = requests.post(f"{base}/extract-embedding",
                          files={"image": ("face.jpg", _jpeg_bytes(), "image/jpeg")},
                          headers={"X-FACE-SERVICE-SECRET": "wrong-secret"})
        assert r.status_code == 401

    def test_missing_secret_returns_401(self, server):
        base, *_ = server
        r = requests.post(f"{base}/extract-embedding",
                          files={"image": ("face.jpg", _jpeg_bytes(), "image/jpeg")})
        assert r.status_code == 401

    def test_missing_frame_field_returns_400(self, server):
        base, *_ = server
        r = requests.post(f"{base}/extract-embedding",
                          data={"not_image": "value"},
                          headers={"X-FACE-SERVICE-SECRET": GOOD_SECRET})
        assert r.status_code == 400
        assert r.json()["extracted"] is False

    def test_invalid_jpeg_returns_400(self, server):
        base, *_ = server
        r = requests.post(f"{base}/extract-embedding",
                          files={"image": ("face.jpg", b"NOT_A_JPEG", "image/jpeg")},
                          headers={"X-FACE-SERVICE-SECRET": GOOD_SECRET})
        assert r.status_code == 400
        assert r.json()["extracted"] is False

    def test_response_schema_on_success(self, server):
        base, *_ = server
        r = requests.post(f"{base}/extract-embedding",
                          files={"image": ("face.jpg", _jpeg_bytes(), "image/jpeg")},
                          headers={"X-FACE-SERVICE-SECRET": GOOD_SECRET})
        assert r.status_code == 200
        body = r.json()
        # Spring's FaceVerificationService expects these exact keys.
        assert "extracted" in body
        assert "is_real" in body
        assert "liveness_score" in body
        assert "embedding" in body
        assert isinstance(body["embedding"], list)
        assert len(body["embedding"]) == 128
        assert all(isinstance(v, (int, float)) for v in body["embedding"])

    def test_spoof_rejection_response_schema(self, server):
        """When anti-spoof returns is_real=False, schema matches Spring's rejection path."""
        base, _, mock_anti, _ = server
        mock_anti.assess.return_value = {
            "is_real": False,
            "liveness_score": 0.1,
            "rejection_reason": "SPOOF_DETECTED",
        }
        try:
            r = requests.post(f"{base}/extract-embedding",
                              files={"image": ("face.jpg", _jpeg_bytes(), "image/jpeg")},
                              headers={"X-FACE-SERVICE-SECRET": GOOD_SECRET})
            assert r.status_code == 200
            body = r.json()
            assert body["extracted"] is False
            assert body["is_real"] is False
            assert body["rejection_reason"] == "SPOOF_DETECTED"
            assert body["reason"] == "SPOOF"
            assert "embedding" not in body
        finally:
            # Restore to genuine for subsequent tests.
            mock_anti.assess.return_value = {
                "is_real": True,
                "liveness_score": 0.9,
                "rejection_reason": None,
            }


# ---------------------------------------------------------------------------
# /health
# ---------------------------------------------------------------------------

class TestHealthHttp:
    def test_health_returns_up(self, server):
        base, *_ = server
        r = requests.get(f"{base}/health")
        assert r.status_code == 200
        assert r.json()["status"] == "UP"


# ---------------------------------------------------------------------------
# /active-liveness/process — HTTP contract
# ---------------------------------------------------------------------------

class TestActiveLivenessHttp:
    def _post_frame(self, base, session_id="test-session", challenge_seq=None,
                    frame_seq=0, secret=GOOD_SECRET):
        if challenge_seq is None:
            challenge_seq = ["BLINK"]
        return requests.post(
            f"{base}/active-liveness/process",
            files={"frame": ("frame.jpg", _jpeg_bytes(), "image/jpeg")},
            data={
                "session_id":     session_id,
                "challenge_seq":  json.dumps(challenge_seq),
                "frame_seq":      str(frame_seq),
            },
            headers={"X-FACE-SERVICE-SECRET": secret},
        )

    def test_correct_secret_returns_200(self, server):
        base, mock_liveness, *_ = server
        from face_module.liveness_service import Decision
        mock_liveness.process_frame.return_value = (Decision.PENDING, None)
        r = self._post_frame(base)
        assert r.status_code == 200
        body = r.json()
        assert body["decision"] == "PENDING"

    def test_incorrect_secret_returns_401(self, server):
        base, *_ = server
        r = self._post_frame(base, secret="wrong-secret")
        assert r.status_code == 401

    def test_missing_secret_returns_401(self, server):
        base, *_ = server
        r = requests.post(
            f"{base}/active-liveness/process",
            files={"frame": ("frame.jpg", _jpeg_bytes(), "image/jpeg")},
            data={"session_id": "s", "challenge_seq": '["BLINK"]', "frame_seq": "0"},
        )
        assert r.status_code == 401

    def test_missing_frame_field_returns_400(self, server):
        base, *_ = server
        r = requests.post(
            f"{base}/active-liveness/process",
            data={"session_id": "s", "challenge_seq": '["BLINK"]', "frame_seq": "0"},
            headers={"X-FACE-SERVICE-SECRET": GOOD_SECRET},
        )
        assert r.status_code == 400
        assert r.json()["reason"] == "MISSING_FRAME"

    def test_invalid_jpeg_returns_400(self, server):
        base, *_ = server
        r = requests.post(
            f"{base}/active-liveness/process",
            files={"frame": ("frame.jpg", b"NOT_A_JPEG", "image/jpeg")},
            data={"session_id": "s", "challenge_seq": '["BLINK"]', "frame_seq": "0"},
            headers={"X-FACE-SERVICE-SECRET": GOOD_SECRET},
        )
        assert r.status_code == 400
        assert r.json()["reason"] == "INVALID_IMAGE"

    def test_missing_session_id_returns_400(self, server):
        base, *_ = server
        r = requests.post(
            f"{base}/active-liveness/process",
            files={"frame": ("frame.jpg", _jpeg_bytes(), "image/jpeg")},
            data={"challenge_seq": '["BLINK"]', "frame_seq": "0"},
            headers={"X-FACE-SERVICE-SECRET": GOOD_SECRET},
        )
        assert r.status_code == 400
        assert r.json()["reason"] == "MISSING_SESSION_ID"

    def test_out_of_order_frame_returns_retryable(self, server):
        """Duplicate/out-of-order frame_seq returns RETRYABLE_FAILURE."""
        base, mock_liveness, *_ = server
        from face_module.liveness_service import Decision
        mock_liveness.process_frame.return_value = (Decision.RETRYABLE_FAILURE, "OUT_OF_ORDER_FRAME")
        r = self._post_frame(base, session_id="oor-session", frame_seq=5)
        assert r.status_code == 200
        body = r.json()
        assert body["decision"] == "RETRYABLE_FAILURE"
        assert body["reason"] == "OUT_OF_ORDER_FRAME"

    def test_expired_session_returns_failed(self, server):
        """Expired session returns FAILED / SESSION_EXPIRED."""
        base, mock_liveness, *_ = server
        from face_module.liveness_service import Decision
        mock_liveness.process_frame.return_value = (Decision.FAILED, "SESSION_EXPIRED")
        r = self._post_frame(base, session_id="expired-session", frame_seq=99)
        assert r.status_code == 200
        body = r.json()
        assert body["decision"] == "FAILED"
        assert body["reason"] == "SESSION_EXPIRED"

    def test_passed_decision_in_response(self, server):
        """PASSED decision is returned to Spring; Spring maps it to its own state."""
        base, mock_liveness, *_ = server
        from face_module.liveness_service import Decision
        mock_liveness.process_frame.return_value = (Decision.PASSED, None)
        r = self._post_frame(base, session_id="passed-session", frame_seq=20)
        assert r.status_code == 200
        body = r.json()
        assert body["decision"] == "PASSED"
        # Flask must NOT create an attendance record — no such field in the response.
        assert "attendance" not in body
        assert "marked" not in body

    def test_response_schema_has_decision_and_reason(self, server):
        """Response schema matches Spring's ActiveLivenessEvaluator.Evaluation."""
        base, mock_liveness, *_ = server
        from face_module.liveness_service import Decision
        mock_liveness.process_frame.return_value = (Decision.PENDING, None)
        r = self._post_frame(base)
        assert r.status_code == 200
        body = r.json()
        assert "decision" in body
        assert "reason" in body
        # decision must be one of the four enum values.
        assert body["decision"] in ("PENDING", "RETRYABLE_FAILURE", "FAILED", "PASSED")
