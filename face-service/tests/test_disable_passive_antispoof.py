"""Tests for the development-only DISABLE_PASSIVE_ANTISPOOF flag.

Covers:
- _is_dev_environment() under all relevant env-var combinations.
- _resolve_disable_passive_antispoof() production guard.
- Config.disable_passive_antispoof resolved correctly from environment.
- Flask endpoint: quality gate still runs when antispoof is disabled.
- Flask endpoint: SFace embedding still required (no auto-accept).
- Flask endpoint: MiniFASNet assess() NOT called when flag is active.
- Flask endpoint: MiniFASNet assess() IS called in production mode.

Run with the face-service venv Python:
    .venv/Scripts/python.exe -m pytest tests/test_disable_passive_antispoof.py -v
"""
from __future__ import annotations

import importlib
import io
import json
import os
import sys
import logging
from dataclasses import replace
from pathlib import Path
from unittest.mock import MagicMock, patch

import numpy as np
import pytest

# Inject face-service root so imports work without conftest.
_root = Path(__file__).resolve().parent.parent
if str(_root) not in sys.path:
    sys.path.insert(0, str(_root))


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _reload_config_module():
    """Re-import config to pick up patched env vars."""
    import face_module.config as m
    importlib.reload(m)
    return m


def _make_tiny_jpeg() -> bytes:
    """Return a minimal valid JPEG (8x8 white square)."""
    import cv2
    arr = np.full((8, 8, 3), 255, dtype=np.uint8)
    ok, buf = cv2.imencode(".jpg", arr)
    assert ok
    return buf.tobytes()


SECRET = "test-secret"


def _bypass_config(app_module):
    """A Config copy with disable_passive_antispoof=True (dev bypass)."""
    return replace(app_module.config, disable_passive_antispoof=True)


def _production_config(app_module):
    """A Config copy with disable_passive_antispoof=False (production)."""
    return replace(app_module.config, disable_passive_antispoof=False)


def _post_extract(client, app_module, jpeg_bytes):
    """POST /extract-embedding with auth bypassed."""
    with patch.object(app_module, "_check_secret", return_value=True):
        return client.post(
            "/extract-embedding",
            data={"image": (io.BytesIO(jpeg_bytes), "face.jpg")},
            content_type="multipart/form-data",
        )


def _fake_face_info():
    """A fake YuNet detection row [x, y, w, h, 10 landmarks, score]."""
    return np.array(
        [10, 10, 20, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.9],
        dtype=np.float32,
    )


# ---------------------------------------------------------------------------
# _is_dev_environment
# ---------------------------------------------------------------------------

class TestIsDevEnvironment:
    def test_flask_env_development_returns_true(self, monkeypatch):
        monkeypatch.setenv("FLASK_ENV", "development")
        monkeypatch.delenv("FLASK_DEBUG", raising=False)
        m = _reload_config_module()
        assert m._is_dev_environment() is True

    def test_flask_env_production_returns_false(self, monkeypatch):
        monkeypatch.setenv("FLASK_ENV", "production")
        monkeypatch.delenv("FLASK_DEBUG", raising=False)
        m = _reload_config_module()
        assert m._is_dev_environment() is False

    def test_flask_env_unset_returns_false(self, monkeypatch):
        monkeypatch.delenv("FLASK_ENV", raising=False)
        monkeypatch.delenv("FLASK_DEBUG", raising=False)
        m = _reload_config_module()
        assert m._is_dev_environment() is False

    def test_flask_debug_1_returns_true(self, monkeypatch):
        monkeypatch.delenv("FLASK_ENV", raising=False)
        monkeypatch.setenv("FLASK_DEBUG", "1")
        m = _reload_config_module()
        assert m._is_dev_environment() is True

    def test_flask_debug_true_returns_true(self, monkeypatch):
        monkeypatch.delenv("FLASK_ENV", raising=False)
        monkeypatch.setenv("FLASK_DEBUG", "true")
        m = _reload_config_module()
        assert m._is_dev_environment() is True

    def test_flask_debug_0_returns_false(self, monkeypatch):
        monkeypatch.delenv("FLASK_ENV", raising=False)
        monkeypatch.setenv("FLASK_DEBUG", "0")
        m = _reload_config_module()
        assert m._is_dev_environment() is False

    def test_flask_env_staging_returns_false(self, monkeypatch):
        monkeypatch.setenv("FLASK_ENV", "staging")
        monkeypatch.delenv("FLASK_DEBUG", raising=False)
        m = _reload_config_module()
        assert m._is_dev_environment() is False


# ---------------------------------------------------------------------------
# _resolve_disable_passive_antispoof -- production guard
# ---------------------------------------------------------------------------

class TestResolveDisablePassiveAntispoof:
    def test_dev_mode_flag_true_returns_true(self, monkeypatch):
        monkeypatch.setenv("FLASK_ENV", "development")
        monkeypatch.setenv("DISABLE_PASSIVE_ANTISPOOF", "true")
        m = _reload_config_module()
        assert m._resolve_disable_passive_antispoof() is True

    def test_dev_mode_flag_false_returns_false(self, monkeypatch):
        monkeypatch.setenv("FLASK_ENV", "development")
        monkeypatch.setenv("DISABLE_PASSIVE_ANTISPOOF", "false")
        m = _reload_config_module()
        assert m._resolve_disable_passive_antispoof() is False

    def test_production_flag_true_still_returns_false(self, monkeypatch):
        """Core safety test: production must NEVER enable the bypass."""
        monkeypatch.setenv("FLASK_ENV", "production")
        monkeypatch.setenv("DISABLE_PASSIVE_ANTISPOOF", "true")
        m = _reload_config_module()
        assert m._resolve_disable_passive_antispoof() is False, (
            "DISABLE_PASSIVE_ANTISPOOF must be ignored in production"
        )

    def test_unset_env_flag_true_still_returns_false(self, monkeypatch):
        """Core safety test: unset FLASK_ENV must also block the bypass."""
        monkeypatch.delenv("FLASK_ENV", raising=False)
        monkeypatch.delenv("FLASK_DEBUG", raising=False)
        monkeypatch.setenv("DISABLE_PASSIVE_ANTISPOOF", "true")
        m = _reload_config_module()
        assert m._resolve_disable_passive_antispoof() is False

    def test_staging_env_flag_true_still_returns_false(self, monkeypatch):
        monkeypatch.setenv("FLASK_ENV", "staging")
        monkeypatch.setenv("DISABLE_PASSIVE_ANTISPOOF", "true")
        m = _reload_config_module()
        assert m._resolve_disable_passive_antispoof() is False

    def test_dev_mode_flag_unset_defaults_to_false(self, monkeypatch):
        monkeypatch.setenv("FLASK_ENV", "development")
        monkeypatch.delenv("DISABLE_PASSIVE_ANTISPOOF", raising=False)
        m = _reload_config_module()
        assert m._resolve_disable_passive_antispoof() is False


# ---------------------------------------------------------------------------
# Config.disable_passive_antispoof resolved from environment
# ---------------------------------------------------------------------------

class TestConfigDisableField:
    def test_config_true_in_dev_with_flag(self, monkeypatch):
        monkeypatch.setenv("FLASK_ENV", "development")
        monkeypatch.setenv("DISABLE_PASSIVE_ANTISPOOF", "true")
        m = _reload_config_module()
        cfg = m.Config.from_environment()
        assert cfg.disable_passive_antispoof is True

    def test_config_false_in_production_with_flag(self, monkeypatch):
        monkeypatch.setenv("FLASK_ENV", "production")
        monkeypatch.setenv("DISABLE_PASSIVE_ANTISPOOF", "true")
        m = _reload_config_module()
        cfg = m.Config.from_environment()
        assert cfg.disable_passive_antispoof is False

    def test_config_false_by_default_in_dev(self, monkeypatch):
        monkeypatch.setenv("FLASK_ENV", "development")
        monkeypatch.delenv("DISABLE_PASSIVE_ANTISPOOF", raising=False)
        m = _reload_config_module()
        cfg = m.Config.from_environment()
        assert cfg.disable_passive_antispoof is False


# ---------------------------------------------------------------------------
# Flask endpoint behaviour
#
# The route reads ``config.disable_passive_antispoof`` from the module global
# at request time.  Instead of reloading app.py (expensive -- re-loads ONNX
# models), we patch the module-level ``config`` object with a dataclass copy
# that has the desired flag value.  This keeps model loading to a single
# import.
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def app_module():
    """Import app once -- ONNX/MediaPipe models load a single time."""
    import app as flask_app_module
    flask_app_module.app.config["TESTING"] = True
    return flask_app_module


class TestExtractEndpointWithBypass:
    """Bypass active (disable_passive_antispoof=True): quality gate runs,
    MiniFASNet assess() is skipped, SFace embedding still required."""

    def test_quality_failure_still_rejected_when_bypass_active(self, app_module):
        client = app_module.app.test_client()
        fake_metrics = {
            "laplacian_var": 5.0, "brightness_mean": 120.0,
            "face_ratio": 0.08, "laplacian_var_raw": 5.0,
        }
        with patch.object(app_module, "config", _bypass_config(app_module)), \
             patch.object(app_module.engine, "detect_single_face_with_box",
                          return_value=_fake_face_info()), \
             patch.object(app_module.anti_spoof_engine, "quality_metrics",
                          return_value=fake_metrics), \
             patch("app._decide_quality", return_value="BLURRY_IMAGE"), \
             patch.object(app_module.anti_spoof_engine, "assess") as mock_assess:

            resp = _post_extract(client, app_module, _make_tiny_jpeg())

        data = json.loads(resp.data)
        assert data["extracted"] is False
        assert data["rejection_reason"] == "BLURRY_IMAGE"
        assert data["reason"] == "QUALITY"
        # assess() must NOT have been called -- MiniFASNet was bypassed
        mock_assess.assert_not_called()

    def test_minifasnet_not_called_when_bypass_active(self, app_module):
        """Core contract: assess() is never invoked when the bypass is active."""
        client = app_module.app.test_client()
        fake_embedding = np.zeros(128, dtype=np.float32)
        fake_metrics = {
            "laplacian_var": 100.0, "brightness_mean": 120.0,
            "face_ratio": 0.08, "laplacian_var_raw": 100.0,
        }
        with patch.object(app_module, "config", _bypass_config(app_module)), \
             patch.object(app_module.engine, "detect_single_face_with_box",
                          return_value=_fake_face_info()), \
             patch.object(app_module.anti_spoof_engine, "quality_metrics",
                          return_value=fake_metrics), \
             patch("app._decide_quality", return_value=None), \
             patch.object(app_module.engine, "extract_embedding",
                          return_value=fake_embedding), \
             patch.object(app_module.anti_spoof_engine, "assess") as mock_assess:

            resp = _post_extract(client, app_module, _make_tiny_jpeg())

        data = json.loads(resp.data)
        assert data["extracted"] is True
        assert data["is_real"] is True
        assert data["liveness_score"] is None
        assert data["passive_antispoof_status"] == "SKIPPED"
        assert len(data["embedding"]) == 128
        mock_assess.assert_not_called()

    def test_sface_still_runs_when_bypass_active(self, app_module):
        """extract_embedding() must be called: identity matching is preserved."""
        client = app_module.app.test_client()
        fake_embedding = np.ones(128, dtype=np.float32)
        fake_metrics = {
            "laplacian_var": 100.0, "brightness_mean": 120.0,
            "face_ratio": 0.08, "laplacian_var_raw": 100.0,
        }
        with patch.object(app_module, "config", _bypass_config(app_module)), \
             patch.object(app_module.engine, "detect_single_face_with_box",
                          return_value=_fake_face_info()), \
             patch.object(app_module.anti_spoof_engine, "quality_metrics",
                          return_value=fake_metrics), \
             patch("app._decide_quality", return_value=None), \
             patch.object(app_module.engine, "extract_embedding",
                          return_value=fake_embedding) as mock_emb, \
             patch.object(app_module.anti_spoof_engine, "assess"):

            resp = _post_extract(client, app_module, _make_tiny_jpeg())

        mock_emb.assert_called_once()
        data = json.loads(resp.data)
        assert data["extracted"] is True
        assert data["liveness_score"] is None
        assert data["passive_antispoof_status"] == "SKIPPED"
        assert data["embedding"][0] == pytest.approx(1.0)


class TestExtractEndpointWithoutBypass:
    """Bypass inactive (production): MiniFASNet assess() IS called and
    spoof detection is fully active."""

    def test_minifasnet_called_in_production_mode(self, app_module):
        """In production mode, assess() must always be called."""
        client = app_module.app.test_client()
        fake_assess = {
            "is_real": True,
            "liveness_score": 0.75,
            "rejection_reason": None,
        }
        with patch.object(app_module, "config", _production_config(app_module)), \
             patch.object(app_module.engine, "detect_single_face_with_box",
                          return_value=_fake_face_info()), \
             patch.object(app_module.anti_spoof_engine, "assess",
                          return_value=fake_assess) as mock_assess, \
             patch.object(app_module.engine, "extract_embedding",
                          return_value=np.zeros(128, dtype=np.float32)):

            resp = _post_extract(client, app_module, _make_tiny_jpeg())

        mock_assess.assert_called_once()
        data = json.loads(resp.data)
        assert data["extracted"] is True

    def test_spoof_rejected_in_production_mode(self, app_module):
        """Production mode must reject a spoof face returned by assess()."""
        client = app_module.app.test_client()
        fake_assess = {
            "is_real": False,
            "liveness_score": 0.12,
            "rejection_reason": "SPOOF_DETECTED",
        }
        with patch.object(app_module, "config", _production_config(app_module)), \
             patch.object(app_module.engine, "detect_single_face_with_box",
                          return_value=_fake_face_info()), \
             patch.object(app_module.anti_spoof_engine, "assess",
                          return_value=fake_assess) as mock_assess, \
             patch.object(app_module.engine, "extract_embedding") as mock_emb:

            resp = _post_extract(client, app_module, _make_tiny_jpeg())

        mock_assess.assert_called_once()
        # SFace extraction must NOT run after a spoof rejection
        mock_emb.assert_not_called()
        data = json.loads(resp.data)
        assert data["extracted"] is False
        assert data["is_real"] is False
        assert data["reason"] == "SPOOF"
        assert data["rejection_reason"] == "SPOOF_DETECTED"


# ---------------------------------------------------------------------------
# Diagnostic logging tests
# ---------------------------------------------------------------------------

class TestDiagnosticLogging:
    """[DIAG] log line fires on quality rejection; successful requests are silent."""

    def test_quality_rejection_emits_diag_log(self, app_module, caplog):
        """A quality rejection must emit a [DIAG] WARNING with the reason and metrics."""
        client = app_module.app.test_client()
        fake_metrics = {
            "laplacian_var": 8.3, "brightness_mean": 115.0,
            "face_ratio": 0.015, "laplacian_var_raw": 8.3,
        }
        with patch.object(app_module, "config", _bypass_config(app_module)), \
             patch.object(app_module.engine, "detect_single_face_with_box",
                          return_value=_fake_face_info()), \
             patch.object(app_module.anti_spoof_engine, "quality_metrics",
                          return_value=fake_metrics), \
             patch("app._decide_quality", return_value="FACE_TOO_SMALL"), \
             patch.object(app_module.anti_spoof_engine, "assess"):

            with caplog.at_level(logging.WARNING, logger="app"):
                _post_extract(client, app_module, _make_tiny_jpeg())

        diag_records = [r for r in caplog.records if "[DIAG]" in r.message]
        assert diag_records, "Expected a [DIAG] WARNING log on quality rejection"
        msg = diag_records[0].message
        assert "FACE_TOO_SMALL" in msg
        assert "laplacian_var=" in msg
        assert "face_ratio=" in msg
        assert "face_box=" in msg
        assert "image=" in msg

    def test_successful_request_no_diag_log(self, app_module, caplog):
        """A passing request must NOT emit a [DIAG] WARNING."""
        client = app_module.app.test_client()
        fake_metrics = {
            "laplacian_var": 120.0, "brightness_mean": 130.0,
            "face_ratio": 0.08, "laplacian_var_raw": 120.0,
        }
        with patch.object(app_module, "config", _bypass_config(app_module)), \
             patch.object(app_module.engine, "detect_single_face_with_box",
                          return_value=_fake_face_info()), \
             patch.object(app_module.anti_spoof_engine, "quality_metrics",
                          return_value=fake_metrics), \
             patch("app._decide_quality", return_value=None), \
             patch.object(app_module.engine, "extract_embedding",
                          return_value=np.zeros(128, dtype=np.float32)), \
             patch.object(app_module.anti_spoof_engine, "assess"):

            with caplog.at_level(logging.WARNING, logger="app"):
                resp = _post_extract(client, app_module, _make_tiny_jpeg())

        data = json.loads(resp.data)
        assert data["extracted"] is True
        diag_warnings = [r for r in caplog.records
                         if r.levelno >= logging.WARNING and "[DIAG]" in r.message]
        assert not diag_warnings, "No [DIAG] WARNING expected for a passing request"
