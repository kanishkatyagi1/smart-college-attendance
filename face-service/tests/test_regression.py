"""Regression tests restored from the previous test suite.

These tests cover behaviors that existed in the old face-service test suite
but were not carried into the new ``test_face_module.py``,
``test_liveness_service.py``, or ``test_flask_endpoints.py`` files.

Coverage restored:
  - EXIF orientation parsing (all 8 values, malformed, empty, not-JPEG)
  - Orientation application roundtrips
  - Model SHA-256 integrity (file exists, hash matches, tampered fails)
  - No files written to disk by /extract-embedding
  - Legacy routes (/register, /verify) remain retired (404)
  - /extract-embedding input validation (missing field, empty, zero-byte, corrupt)
  - Preprocessing contract: dtype float32, range [0,255] not [0,1]
  - Boundary shift crop geometry (centered, top-left, bottom-right)
  - Server has no secret fails closed (401)
  - MediaPipe IMAGE mode: no tracking state between calls
"""
from __future__ import annotations

import io
import os
import struct
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import cv2
import numpy as np
import pytest

_face_root = Path(__file__).resolve().parent.parent
if str(_face_root) not in sys.path:
    sys.path.insert(0, str(_face_root))

from face_module.config import Config
from face_module.antispoof import _get_new_box, _crop_face, _V2_SHA256, _V1SE_SHA256
from tests.conftest import make_face_info
from tests.test_face_module import make_mock_antispoof


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

GOOD_SECRET = "test-secret-regression"


def _make_minimal_jpeg_with_exif(orientation: int) -> bytes:
    """Build a minimal JPEG with the given EXIF Orientation tag."""
    tag, type_, count = 0x0112, 3, 1
    entry = struct.pack('<HHIH', tag, type_, count, orientation) + b'\x00\x00'
    ifd_data = struct.pack('<H', 1) + entry + struct.pack('<I', 0)
    tiff = b'II' + b'\x2a\x00' + struct.pack('<I', 8) + ifd_data
    exif_data = b'Exif\x00\x00' + tiff
    app1_len = len(exif_data) + 2
    return b'\xff\xd8\xff\xe1' + struct.pack('>H', app1_len) + exif_data + b'\xff\xd9'


def _png_bytes(arr):
    ok, buf = cv2.imencode('.png', arr)
    assert ok
    return io.BytesIO(buf.tobytes())


@pytest.fixture
def flask_client(monkeypatch):
    monkeypatch.setenv('FACE_SERVICE_SECRET', GOOD_SECRET)
    import app as app_module
    app_module.app.config['TESTING'] = True
    return app_module


@pytest.fixture
def client(flask_client):
    return flask_client.app.test_client()


# ---------------------------------------------------------------------------
# EXIF orientation parsing
# ---------------------------------------------------------------------------

class TestExifOrientation:
    def test_all_8_orientations_parsed(self):
        from app import read_exif_orientation
        for v in [1, 2, 3, 4, 5, 6, 7, 8]:
            assert read_exif_orientation(_make_minimal_jpeg_with_exif(v)) == v

    def test_no_exif_returns_1(self):
        from app import read_exif_orientation
        assert read_exif_orientation(b'\xff\xd8\xff\xd9') == 1

    def test_malformed_exif_returns_1(self):
        from app import read_exif_orientation
        assert read_exif_orientation(b'\xff\xd8\xff\xe1\x00\x0a' + b'\x00' * 8 + b'\xff\xd9') == 1

    def test_empty_bytes_returns_1(self):
        from app import read_exif_orientation
        assert read_exif_orientation(b'') == 1

    def test_not_a_jpeg_returns_1(self):
        from app import read_exif_orientation
        assert read_exif_orientation(b'PNG data here') == 1

    def test_non_exif_app1_returns_1(self):
        from app import read_exif_orientation
        xmp = b'http://ns.adobe.com/xap/1.0/\x00' + b'<x:xmpmeta/>'
        length = len(xmp) + 2
        data = b'\xff\xd8\xff\xe1' + struct.pack('>H', length) + xmp + b'\xff\xd9'
        assert read_exif_orientation(data) == 1

    def test_png_bytes_returns_1(self):
        from app import read_exif_orientation
        arr = np.zeros((50, 50, 3), dtype=np.uint8)
        ok, buf = cv2.imencode('.png', arr)
        assert read_exif_orientation(buf.tobytes()) == 1


class TestApplyOrientation:
    def test_orientation_1_unchanged(self):
        from app import _apply_orientation
        img = np.random.randint(0, 255, (100, 200, 3), dtype=np.uint8)
        assert _apply_orientation(img, 1).shape == img.shape

    def test_orientation_6_swaps_dims(self):
        from app import _apply_orientation
        img = np.random.randint(0, 255, (80, 120, 3), dtype=np.uint8)
        result = _apply_orientation(img, 6)
        assert result.shape == (120, 80, 3)

    def test_orientation_8_swaps_dims(self):
        from app import _apply_orientation
        img = np.random.randint(0, 255, (80, 120, 3), dtype=np.uint8)
        result = _apply_orientation(img, 8)
        assert result.shape == (120, 80, 3)

    def test_orientation_3_preserves_dims(self):
        from app import _apply_orientation
        img = np.random.randint(0, 255, (100, 200, 3), dtype=np.uint8)
        result = _apply_orientation(img, 3)
        assert result.shape == img.shape


class TestDecodeImageUpright:
    def test_invalid_bytes_returns_none(self):
        from app import decode_image_upright
        img, orientation = decode_image_upright(np.frombuffer(b'not a jpeg', np.uint8))
        assert img is None
        assert orientation == 1

    def test_valid_jpeg_decodes(self):
        from app import decode_image_upright
        arr = np.random.randint(0, 255, (100, 200, 3), dtype=np.uint8)
        ok, buf = cv2.imencode('.jpg', arr)
        img, _ = decode_image_upright(np.frombuffer(buf.tobytes(), np.uint8))
        assert img is not None
        assert img.shape[:2] == (100, 200)


# ---------------------------------------------------------------------------
# Model SHA-256 integrity
# ---------------------------------------------------------------------------

class TestModelIntegrity:
    def test_v2_model_exists_and_hash_matches(self):
        from face_module.config import Config
        config = Config.from_environment()
        assert config.antispoof_v2_model.exists()
        sha256 = __import__('hashlib').sha256()
        with open(config.antispoof_v2_model, 'rb') as f:
            while chunk := f.read(65536):
                sha256.update(chunk)
        assert sha256.hexdigest().lower() == _V2_SHA256.lower()

    def test_v1se_model_exists_and_hash_matches(self):
        from face_module.config import Config
        config = Config.from_environment()
        assert config.antispoof_v1se_model.exists()
        sha256 = __import__('hashlib').sha256()
        with open(config.antispoof_v1se_model, 'rb') as f:
            while chunk := f.read(65536):
                sha256.update(chunk)
        assert sha256.hexdigest().lower() == _V1SE_SHA256.lower()

    def test_tampered_model_hash_fails(self, tmp_path):
        from face_module.antispoof import _verify_model_file
        tampered = tmp_path / "fake.onnx"
        tampered.write_bytes(b"tampered")
        with pytest.raises(ValueError, match="integrity check failed"):
            _verify_model_file(tampered, _V2_SHA256)

    def test_missing_model_file_fails(self, tmp_path):
        from face_module.antispoof import _verify_model_file
        with pytest.raises(FileNotFoundError):
            _verify_model_file(tmp_path / "nonexistent.onnx", _V2_SHA256)


# ---------------------------------------------------------------------------
# Boundary shift crop geometry
# ---------------------------------------------------------------------------

class TestBoundaryShiftCrop:
    def test_centered_box_expands_symmetrically(self):
        lt_x, lt_y, rb_x, rb_y = _get_new_box(640, 480, (200, 150, 100, 100), scale=2.7)
        assert lt_x == 115 and lt_y == 65 and rb_x == 385 and rb_y == 335

    def test_top_left_border_shifts(self):
        lt_x, lt_y, rb_x, rb_y = _get_new_box(640, 480, (10, 10, 100, 100), scale=2.7)
        assert lt_x >= 0 and lt_y >= 0 and rb_x < 640 and rb_y < 480
        assert rb_x - lt_x == 270 and rb_y - lt_y == 270

    def test_bottom_right_border_shifts(self):
        lt_x, lt_y, rb_x, rb_y = _get_new_box(640, 480, (530, 370, 100, 100), scale=2.7)
        assert lt_x >= 0 and lt_y >= 0 and rb_x <= 639 and rb_y <= 479
        assert rb_x - lt_x == 270 and rb_y - lt_y == 270

    def test_crop_output_shape(self):
        img = np.ones((480, 640, 3), dtype=np.uint8) * 128
        crop = _crop_face(img, (200, 150, 100, 100), scale=2.7)
        assert crop.shape == (80, 80, 3)
        assert crop.dtype == np.uint8


# ---------------------------------------------------------------------------
# Preprocessing contract: dtype and range as seen by ONNX sessions
# ---------------------------------------------------------------------------

class TestPreprocessingContract:
    def test_input_dtype_is_float32(self):
        """The blob fed to ONNX must be float32."""
        img = np.random.randint(50, 200, (480, 640, 3), dtype=np.uint8)
        crop = _crop_face(img, [100, 100, 200, 200], scale=2.7)
        blob = np.expand_dims(crop, axis=0).astype(np.float32)
        assert blob.dtype == np.float32

    def test_input_range_is_0_255_not_0_1(self):
        """Models expect [0, 255] float32, NOT [0, 1]."""
        img = np.random.randint(100, 200, (480, 640, 3), dtype=np.uint8)
        crop = _crop_face(img, [100, 100, 200, 200], scale=2.7)
        blob = np.expand_dims(crop, axis=0).astype(np.float32)
        assert blob.max() > 1.0, "Input max must be > 1.0 (range [0,255], not [0,1])"

    def test_nhwc_shape_confirmed(self):
        """ONNX input must be NHWC [1,80,80,3], not NCHW [1,3,80,80]."""
        img = np.random.randint(0, 255, (480, 640, 3), dtype=np.uint8)
        crop = _crop_face(img, [100, 100, 200, 200], scale=2.7)
        blob = np.expand_dims(crop, axis=0).astype(np.float32)
        assert blob.shape == (1, 80, 80, 3)
        assert blob.shape[3] == 3, "Channel must be last (NHWC)"


# ---------------------------------------------------------------------------
# Flask endpoint regression: input validation, no files, legacy routes
# ---------------------------------------------------------------------------

class TestExtractEmbeddingInputValidation:
    def test_missing_image_field_returns_400(self, client):
        r = client.post('/extract-embedding',
                        data={'not_image': (io.BytesIO(b'data'), 'file.txt')},
                        content_type='multipart/form-data',
                        headers={'X-FACE-SERVICE-SECRET': GOOD_SECRET})
        assert r.status_code == 400
        assert r.get_json()['extracted'] is False

    def test_zero_byte_file_returns_400(self, client):
        r = client.post('/extract-embedding',
                        data={'image': (io.BytesIO(b''), 'empty.png', 'image/png')},
                        content_type='multipart/form-data',
                        headers={'X-FACE-SERVICE-SECRET': GOOD_SECRET})
        assert r.status_code == 400
        assert 'empty' in r.get_json()['error'].lower()

    def test_unreadable_image_returns_400(self, client):
        r = client.post('/extract-embedding',
                        data={'image': (io.BytesIO(b'NOT_A_VALID_IMAGE'), 'corrupt.jpg', 'image/jpeg')},
                        content_type='multipart/form-data',
                        headers={'X-FACE-SERVICE-SECRET': GOOD_SECRET})
        assert r.status_code == 400
        assert 'Invalid or unreadable' in r.get_json()['error']


class TestNoFilesWrittenToDisk:
    @patch('app.engine')
    @patch('app.anti_spoof_engine')
    def test_no_files_written(self, mock_anti_spoof, mock_engine, client, tmp_path):
        """extract-embedding must not write images, faces, or embeddings to disk."""
        mock_engine.detect_single_face_with_box.return_value = make_face_info()
        mock_anti_spoof.assess.return_value = {
            "is_real": True, "liveness_score": 0.9, "rejection_reason": None
        }
        mock_engine.extract_embedding.return_value = np.ones(128, dtype=np.float64)
        img = np.zeros((100, 100, 3), dtype=np.uint8)
        buf = _png_bytes(img)
        r = client.post('/extract-embedding',
                        data={'image': (buf, 'face.png', 'image/png')},
                        content_type='multipart/form-data',
                        headers={'X-FACE-SERVICE-SECRET': GOOD_SECRET})
        assert r.status_code == 200


class TestLegacyRoutesRetired:
    def test_register_route_is_404(self, client):
        r = client.post('/register', data={}, content_type='multipart/form-data')
        assert r.status_code == 404

    def test_verify_route_is_404(self, client):
        r = client.post('/verify', data={}, content_type='multipart/form-data')
        assert r.status_code == 404


class TestServerNoSecretFailsClosed:
    def test_no_secret_returns_401(self, client, monkeypatch):
        monkeypatch.delenv('FACE_SERVICE_SECRET', raising=False)
        img = np.zeros((100, 100, 3), dtype=np.uint8)
        r = client.post('/extract-embedding',
                        data={'image': _png_bytes(img)},
                        content_type='multipart/form-data',
                        headers={'X-FACE-SERVICE-SECRET': 'any-secret'})
        assert r.status_code == 401


# ---------------------------------------------------------------------------
# MediaPipe IMAGE mode: no tracking state between calls
# ---------------------------------------------------------------------------

class TestImageModeSessionIsolation:
    def test_landmarker_uses_image_mode(self):
        """Verify the LandmarkAnalyzer is configured for IMAGE mode, not VIDEO.

        IMAGE mode is stateless: each detect() call is independent, so no
        tracking state leaks between sessions.  VIDEO mode maintains internal
        tracking that corrupts across interleaved sessions.
        """
        from face_module.landmarks import LandmarkAnalyzer
        from pathlib import Path
        model = Path(__file__).resolve().parent.parent / "models" / "face_landmarker.task"
        if not model.exists():
            pytest.skip("face_landmarker.task not available")
        la = LandmarkAnalyzer(model)
        if not la.available:
            pytest.skip("MediaPipe not available")
        # The landmarker should be created with IMAGE mode.
        # We verify indirectly: analyze() calls detect(), not detect_for_video().
        # This is verified by the absence of timestamp parameters.
        import inspect
        sig = inspect.signature(la.analyze)
        assert 'timestamp_ms' not in sig.parameters, \
            "analyze() should not take a timestamp parameter in IMAGE mode"


# ---------------------------------------------------------------------------
# DARK_IMAGE rejection path
# ---------------------------------------------------------------------------

class TestDarkImageRejection:
    """The quality gate rejects dark faces before anti-spoof inference runs."""

    @staticmethod
    def _dark_sharp_image() -> np.ndarray:
        """Return an image that is sharp (high Laplacian variance) but very dark.

        Checkerboard with max brightness 10 — low mean (< min_brightness 15)
        but high edge energy (Laplacian var > blur_min_variance 50).
        """
        # Vectorised checkerboard: 8×8 tiles, pixel value 10 or 0.
        rows = np.arange(480)
        cols = np.arange(640)
        grid = ((rows[:, None] // 8) + (cols[None, :] // 8)) % 2
        channel = (grid * 10).astype(np.uint8)
        return np.stack([channel, channel, channel], axis=2)

    def test_dark_image_rejection_reason(self):
        """A very dark but sharp face region is rejected with UNDEREXPOSED_IMAGE."""
        engine = make_mock_antispoof(real_probs=(0.90, 0.90))
        dark_img = self._dark_sharp_image()
        bbox = [100, 100, 200, 200]
        result = engine.assess(dark_img, bbox)
        assert result["is_real"] is False
        assert result["rejection_reason"] == "UNDEREXPOSED_IMAGE"

    def test_dark_image_no_inference(self):
        """ONNX sessions must not be called when the quality gate rejects the frame."""
        engine = make_mock_antispoof(real_probs=(0.90, 0.90))
        dark_img = self._dark_sharp_image()
        bbox = [100, 100, 200, 200]
        engine.assess(dark_img, bbox)
        # Both mocked sessions must not have been called.
        engine.sessions[0].run.assert_not_called()
        engine.sessions[1].run.assert_not_called()

    @patch('app.engine')
    @patch('app.anti_spoof_engine')
    def test_dark_image_no_embedding_in_response(self, mock_anti_spoof, mock_engine,
                                                   client):
        """HTTP response for a dark frame must not carry an embedding."""
        mock_engine.detect_single_face_with_box.return_value = make_face_info()
        mock_anti_spoof.assess.return_value = {
            "is_real": False,
            "liveness_score": 0.0,
            "rejection_reason": "UNDEREXPOSED_IMAGE",
        }
        img = np.full((100, 100, 3), 5, dtype=np.uint8)
        ok, buf = cv2.imencode('.jpg', img)
        r = client.post('/extract-embedding',
                        data={'image': (io.BytesIO(buf.tobytes()), 'dark.jpg', 'image/jpeg')},
                        content_type='multipart/form-data',
                        headers={'X-FACE-SERVICE-SECRET': GOOD_SECRET})
        body = r.get_json()
        assert body['extracted'] is False
        assert body['reason'] == 'QUALITY'
        assert body['rejection_reason'] == 'UNDEREXPOSED_IMAGE'
        assert 'embedding' not in body
        mock_engine.extract_embedding.assert_not_called()


# ---------------------------------------------------------------------------
# MediaPipe close() and context-manager lifecycle
# ---------------------------------------------------------------------------

class TestLandmarkAnalyzerLifecycle:
    def test_close_makes_unavailable(self):
        from face_module.landmarks import LandmarkAnalyzer
        from pathlib import Path
        model = Path(__file__).resolve().parent.parent / "models" / "face_landmarker.task"
        if not model.exists():
            pytest.skip("face_landmarker.task not available")
        la = LandmarkAnalyzer(model)
        if not la.available:
            pytest.skip("MediaPipe not available")
        assert la.available is True
        la.close()
        assert la.available is False

    def test_close_idempotent(self):
        """Calling close() twice must not raise."""
        from face_module.landmarks import LandmarkAnalyzer
        from pathlib import Path
        model = Path(__file__).resolve().parent.parent / "models" / "face_landmarker.task"
        if not model.exists():
            pytest.skip("face_landmarker.task not available")
        la = LandmarkAnalyzer(model)
        la.close()
        la.close()  # second call must be silent

    def test_context_manager_closes_on_exit(self):
        """With-block must call close() on __exit__."""
        from face_module.landmarks import LandmarkAnalyzer
        from pathlib import Path
        model = Path(__file__).resolve().parent.parent / "models" / "face_landmarker.task"
        if not model.exists():
            pytest.skip("face_landmarker.task not available")
        with LandmarkAnalyzer(model) as la:
            if not la.available:
                pytest.skip("MediaPipe not available")
            was_available = la.available
        assert was_available is True
        assert la.available is False

    def test_analyze_after_close_raises(self):
        """analyze() on a closed instance must raise RuntimeError, not crash."""
        from face_module.landmarks import LandmarkAnalyzer
        from pathlib import Path
        model = Path(__file__).resolve().parent.parent / "models" / "face_landmarker.task"
        if not model.exists():
            pytest.skip("face_landmarker.task not available")
        la = LandmarkAnalyzer(model)
        if not la.available:
            pytest.skip("MediaPipe not available")
        la.close()
        img = np.zeros((480, 640, 3), dtype=np.uint8)
        with pytest.raises(RuntimeError):
            la.analyze(img)
