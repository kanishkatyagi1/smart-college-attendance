"""Flask endpoint tests (tests 19-24): auth, integration, contract compatibility.

Tests mock the model engines and use the Flask test client.  No real models
or biometric data are required.
"""
from __future__ import annotations

import io
import os
from unittest.mock import MagicMock, patch

import cv2
import numpy as np
import pytest

from tests.conftest import make_face_info


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def jpeg_image():
    """Return JPEG bytes for a 480x640 BGR image."""
    img = np.random.randint(0, 255, (480, 640, 3), dtype=np.uint8)
    _, buf = cv2.imencode('.jpg', img)
    return buf.tobytes()


@pytest.fixture
def flask_client(monkeypatch):
    """Create a Flask test client with FACE_SERVICE_SECRET set."""
    monkeypatch.setenv('FACE_SERVICE_SECRET', 'test-secret-12345')
    import app as app_module
    app_module.app.config['TESTING'] = True
    return app_module


@pytest.fixture
def client(flask_client):
    return flask_client.app.test_client()


def make_genuine_response():
    """Mock anti-spoof response for a genuine face."""
    return {
        "is_real": True,
        "liveness_score": 0.82,
        "rejection_reason": None,
    }


def make_spoof_response():
    """Mock anti-spoof response for a spoof."""
    return {
        "is_real": False,
        "liveness_score": 0.12,
        "rejection_reason": "SPOOF_DETECTED",
    }


# ---------------------------------------------------------------------------
# Test 19: Missing Flask secret
# ---------------------------------------------------------------------------

def test_missing_flask_secret(client, jpeg_image, monkeypatch):
    """Test 19: Request without X-FACE-SERVICE-SECRET returns 401."""
    monkeypatch.delenv('FACE_SERVICE_SECRET', raising=False)
    data = {'image': (io.BytesIO(jpeg_image), 'test.jpg')}
    response = client.post('/extract-embedding', data=data,
                           content_type='multipart/form-data')
    assert response.status_code == 401
    assert response.get_json()['extracted'] is False


# ---------------------------------------------------------------------------
# Test 20: Wrong Flask secret
# ---------------------------------------------------------------------------

def test_wrong_flask_secret(client, jpeg_image):
    """Test 20: Request with wrong X-FACE-SERVICE-SECRET returns 401."""
    data = {'image': (io.BytesIO(jpeg_image), 'test.jpg')}
    response = client.post('/extract-embedding', data=data,
                           headers={'X-FACE-SERVICE-SECRET': 'wrong-secret'},
                           content_type='multipart/form-data')
    assert response.status_code == 401


# ---------------------------------------------------------------------------
# Test 21: Spring-to-Flask authenticated integration
# ---------------------------------------------------------------------------

@patch('app.engine')
@patch('app.anti_spoof_engine')
def test_authenticated_extract_embedding(mock_anti_spoof, mock_engine, client, jpeg_image):
    """Test 21: Authenticated request with correct secret succeeds."""
    mock_engine.detect_single_face_with_box.return_value = make_face_info()
    mock_anti_spoof.assess.return_value = make_genuine_response()
    mock_engine.extract_embedding.return_value = np.ones(128, dtype=np.float64) * 0.5

    data = {'image': (io.BytesIO(jpeg_image), 'test.jpg')}
    response = client.post('/extract-embedding', data=data,
                           headers={'X-FACE-SERVICE-SECRET': 'test-secret-12345'},
                           content_type='multipart/form-data')
    assert response.status_code == 200
    body = response.get_json()
    assert body['extracted'] is True
    assert body['is_real'] is True
    assert len(body['embedding']) == 128
    assert all(isinstance(v, float) for v in body['embedding'])


@patch('app.engine')
@patch('app.anti_spoof_engine')
@patch('app.liveness_service')
def test_authenticated_active_liveness(mock_liveness, mock_anti_spoof, mock_engine,
                                        client, jpeg_image):
    """Test 21b: Authenticated active-liveness endpoint returns a decision."""
    from face_module.liveness_service import Decision
    mock_engine.detect_single_face_with_box.return_value = make_face_info()
    mock_anti_spoof.check_quality.return_value = None
    mock_anti_spoof.available = True
    mock_anti_spoof.score.return_value = 0.9
    mock_engine.is_available = True

    mock_landmarks = MagicMock()
    mock_landmarks.available = True
    mock_landmarks.analyze.return_value = MagicMock(
        blink_left=0.0, blink_right=0.0, yaw=0.0, pitch=0.0, roll=0.0,
        glasses_score=0.0, eye_region_quality=0.0, bridge_region_score=0.0,
    )
    with patch('app.landmark_analyzer', mock_landmarks):
        mock_liveness.process_frame.return_value = (Decision.PENDING, None)

        data = {
            'frame': (io.BytesIO(jpeg_image), 'frame.jpg'),
            'session_id': 'test-session',
            'challenge_seq': '["BLINK"]',
            'frame_seq': '0',
        }
        response = client.post('/active-liveness/process', data=data,
                               headers={'X-FACE-SERVICE-SECRET': 'test-secret-12345'},
                               content_type='multipart/form-data')
        assert response.status_code == 200
        body = response.get_json()
        assert body['decision'] == 'PENDING'


# ---------------------------------------------------------------------------
# Test 22: No SFace matching after liveness rejection
# ---------------------------------------------------------------------------

@patch('app.engine')
@patch('app.anti_spoof_engine')
def test_no_embedding_after_liveness_rejection(mock_anti_spoof, mock_engine, client, jpeg_image):
    """Test 22: SPOOF_DETECTED returns no embedding; extract_embedding never called."""
    mock_engine.detect_single_face_with_box.return_value = make_face_info()
    mock_anti_spoof.assess.return_value = make_spoof_response()

    data = {'image': (io.BytesIO(jpeg_image), 'test.jpg')}
    response = client.post('/extract-embedding', data=data,
                           headers={'X-FACE-SERVICE-SECRET': 'test-secret-12345'},
                           content_type='multipart/form-data')
    assert response.status_code == 200
    body = response.get_json()
    assert body['extracted'] is False
    assert body['is_real'] is False
    assert body['rejection_reason'] == 'SPOOF_DETECTED'
    assert 'embedding' not in body
    # SFace extraction must never run after a liveness rejection
    mock_engine.extract_embedding.assert_not_called()


# ---------------------------------------------------------------------------
# Test 23: Existing registration compatibility
# ---------------------------------------------------------------------------

@patch('app.engine')
@patch('app.anti_spoof_engine')
def test_registration_contract(mock_anti_spoof, mock_engine, client, jpeg_image):
    """Test 23: /extract-embedding response format is compatible with Spring registration.

    Spring's FaceRegistrationService expects:
      - 128-element embedding list on success
      - is_real=true, liveness_score float
    """
    mock_engine.detect_single_face_with_box.return_value = make_face_info()
    mock_anti_spoof.assess.return_value = {
        "is_real": True,
        "liveness_score": 0.85,
        "rejection_reason": None,
    }
    mock_engine.extract_embedding.return_value = np.linspace(-1, 1, 128, dtype=np.float64)

    data = {'image': (io.BytesIO(jpeg_image), 'register.jpg')}
    response = client.post('/extract-embedding', data=data,
                           headers={'X-FACE-SERVICE-SECRET': 'test-secret-12345'},
                           content_type='multipart/form-data')
    assert response.status_code == 200
    body = response.get_json()
    assert body['extracted'] is True
    assert body['is_real'] is True
    assert isinstance(body['liveness_score'], (int, float))
    assert len(body['embedding']) == 128
    # All embedding values must be finite floats (Spring encrypts with AES-256-GCM)
    assert all(np.isfinite(v) for v in body['embedding'])


# ---------------------------------------------------------------------------
# Test 24: Existing attendance verification compatibility
# ---------------------------------------------------------------------------

@patch('app.engine')
@patch('app.anti_spoof_engine')
def test_verification_contract_success(mock_anti_spoof, mock_engine, client, jpeg_image):
    """Test 24a: Verification success returns embedding for cosine matching."""
    mock_engine.detect_single_face_with_box.return_value = make_face_info()
    mock_anti_spoof.assess.return_value = {
        "is_real": True,
        "liveness_score": 0.78,
        "rejection_reason": None,
    }
    mock_engine.extract_embedding.return_value = np.ones(128, dtype=np.float64) * 0.3

    data = {'image': (io.BytesIO(jpeg_image), 'verify.jpg')}
    response = client.post('/extract-embedding', data=data,
                           headers={'X-FACE-SERVICE-SECRET': 'test-secret-12345'},
                           content_type='multipart/form-data')
    body = response.get_json()
    assert body['extracted'] is True
    assert body['is_real'] is True
    # Spring computes cosine similarity against the stored encrypted embedding
    assert len(body['embedding']) == 128


@patch('app.engine')
@patch('app.anti_spoof_engine')
def test_verification_contract_spoof_rejection(mock_anti_spoof, mock_engine, client, jpeg_image):
    """Test 24b: Verification with spoof returns structured rejection, not 500."""
    mock_engine.detect_single_face_with_box.return_value = make_face_info()
    mock_anti_spoof.assess.return_value = {
        "is_real": False,
        "liveness_score": 0.05,
        "rejection_reason": "SPOOF_DETECTED",
    }

    data = {'image': (io.BytesIO(jpeg_image), 'spoof.jpg')}
    response = client.post('/extract-embedding', data=data,
                           headers={'X-FACE-SERVICE-SECRET': 'test-secret-12345'},
                           content_type='multipart/form-data')
    assert response.status_code == 200  # structured rejection, not error
    body = response.get_json()
    assert body['extracted'] is False
    assert body['is_real'] is False
    assert body['rejection_reason'] == 'SPOOF_DETECTED'
    assert body['reason'] == 'SPOOF'


@patch('app.engine')
@patch('app.anti_spoof_engine')
def test_verification_contract_quality_rejection(mock_anti_spoof, mock_engine, client, jpeg_image):
    """Test 24c: Quality failure returns QUALITY reason, not SPOOF."""
    mock_engine.detect_single_face_with_box.return_value = make_face_info()
    mock_anti_spoof.assess.return_value = {
        "is_real": False,
        "liveness_score": 0.0,
        "rejection_reason": "BLURRY_IMAGE",
    }

    data = {'image': (io.BytesIO(jpeg_image), 'blurry.jpg')}
    response = client.post('/extract-embedding', data=data,
                           headers={'X-FACE-SERVICE-SECRET': 'test-secret-12345'},
                           content_type='multipart/form-data')
    body = response.get_json()
    assert body['reason'] == 'QUALITY'
    assert body['rejection_reason'] == 'BLURRY_IMAGE'


# ---------------------------------------------------------------------------
# Extra: Health endpoint
# ---------------------------------------------------------------------------

def test_health_endpoint(client):
    """Health endpoint returns UP status."""
    response = client.get('/health')
    assert response.status_code == 200
    body = response.get_json()
    assert body['status'] == 'UP'


# ---------------------------------------------------------------------------
# Extra: Active-liveness endpoint auth
# ---------------------------------------------------------------------------

def test_active_liveness_missing_secret(client, jpeg_image, monkeypatch):
    """Active-liveness endpoint requires X-FACE-SERVICE-SECRET."""
    monkeypatch.delenv('FACE_SERVICE_SECRET', raising=False)
    data = {
        'frame': (io.BytesIO(jpeg_image), 'frame.jpg'),
        'session_id': 'test',
        'challenge_seq': '["BLINK"]',
        'frame_seq': '0',
    }
    response = client.post('/active-liveness/process', data=data,
                            content_type='multipart/form-data')
    assert response.status_code == 401
