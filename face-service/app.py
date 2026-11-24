"""Flask face-service: passive anti-spoof embedding extraction + active liveness.

Endpoints
---------
``GET  /health``                          – service health check.
``POST /extract-embedding``                – authenticated, Spring-only.  Runs YuNet
                                           detection, MiniFASNet ensemble passive
                                           anti-spoof, then SFace 128-dim embedding.
``POST /active-liveness/process``          – authenticated, Spring-only.  Processes
                                           one frame for a Spring-owned active
                                           liveness session and returns a decision.

Authentication
-------------
All data endpoints require ``X-FACE-SERVICE-SECRET``.  Spring Boot is the only
public API boundary; Android never calls Flask directly.

Storage boundary
----------------
Flask never stores images, embeddings, or biometric data.  Spring Boot owns
AES-256-GCM encrypted embeddings in MySQL.
"""
from __future__ import annotations

import hmac
import logging
import os
import struct
from pathlib import Path

import cv2
import numpy as np
from flask import Flask, request, jsonify

from face_module.config import Config
from face_module.recognition import FaceEngine, FaceDetectionError, FaceError
from face_module.antispoof import AntiSpoofEngine, _decide_quality
from face_module.landmarks import LandmarkAnalyzer
from face_module.liveness_service import LivenessService, Decision

app = Flask(__name__)
app.logger.setLevel(logging.INFO)

# ---------------------------------------------------------------------------
# Model loading (at startup)
# ---------------------------------------------------------------------------
config = Config.from_environment()
engine = FaceEngine(config)
anti_spoof_engine = AntiSpoofEngine(config)
landmark_analyzer = LandmarkAnalyzer(config.landmark_model)
liveness_service = LivenessService(config, engine, landmark_analyzer, anti_spoof_engine)

if not engine.is_available:
    app.logger.error("FaceEngine unavailable: %s", engine.unavailable_reason)
if not anti_spoof_engine.available:
    app.logger.error("AntiSpoofEngine unavailable: %s", anti_spoof_engine.reason)
if not landmark_analyzer.available:
    app.logger.warning("LandmarkAnalyzer unavailable: %s", landmark_analyzer.reason)


# ---------------------------------------------------------------------------
# Deterministic cleanup on application teardown
# ---------------------------------------------------------------------------

import atexit

@atexit.register
def _shutdown():
    """Close MediaPipe resources explicitly on process exit.

    This prevents the ``TypeError: 'NoneType' object is not callable`` that
    MediaPipe 1.2.x raises inside ``FaceLandmarker.__del__`` when the Python
    interpreter tears down C-extension callbacks before the GC runs the
    finaliser.  Calling ``close()`` while the interpreter is still healthy
    releases the native graph deterministically.
    """
    landmark_analyzer.close()


# ---------------------------------------------------------------------------
# EXIF-aware image decode (preserved from production)
# ---------------------------------------------------------------------------

def read_exif_orientation(data: bytes) -> int:
    """Return the EXIF Orientation tag value (1-8), or 1 if absent."""
    try:
        if len(data) < 4 or data[:2] != b'\xff\xd8':
            return 1
        offset = 2
        while offset + 4 <= len(data):
            marker = data[offset:offset + 2]
            if len(data) < offset + 4:
                break
            seg_len = struct.unpack('>H', data[offset + 2:offset + 4])[0]
            if marker == b'\xff\xe1':
                app1 = data[offset + 4:offset + 2 + seg_len]
                if app1[:6] != b'Exif\x00\x00':
                    break
                tiff = app1[6:]
                if len(tiff) < 8:
                    break
                endian = '<' if tiff[:2] == b'II' else ('>' if tiff[:2] == b'MM' else None)
                if endian is None:
                    break
                ifd_off = struct.unpack(endian + 'I', tiff[4:8])[0]
                if ifd_off + 2 > len(tiff):
                    break
                n = struct.unpack(endian + 'H', tiff[ifd_off:ifd_off + 2])[0]
                for i in range(n):
                    es = ifd_off + 2 + i * 12
                    if es + 12 > len(tiff):
                        break
                    tag = struct.unpack(endian + 'H', tiff[es:es + 2])[0]
                    if tag == 0x0112:
                        return int(struct.unpack(endian + 'H', tiff[es + 8:es + 10])[0])
                break
            offset += 2 + seg_len
    except Exception:
        pass
    return 1


def _apply_orientation(img: np.ndarray, orientation: int) -> np.ndarray:
    if orientation == 1:
        return img
    if orientation == 2:
        return cv2.flip(img, 1)
    if orientation == 3:
        return cv2.rotate(img, cv2.ROTATE_180)
    if orientation == 4:
        return cv2.flip(img, 0)
    if orientation == 5:
        return cv2.flip(cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE), 1)
    if orientation == 6:
        return cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
    if orientation == 7:
        return cv2.flip(cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE), 1)
    if orientation == 8:
        return cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE)
    return img


def decode_image_upright(raw_array: np.ndarray):
    """Decode image bytes into an upright BGR image, applying EXIF orientation."""
    raw_bytes = bytes(raw_array)
    orientation = read_exif_orientation(raw_bytes)
    img = cv2.imdecode(
        np.frombuffer(raw_bytes, np.uint8),
        cv2.IMREAD_COLOR | cv2.IMREAD_IGNORE_ORIENTATION,
    )
    if img is None:
        return None, orientation
    return _apply_orientation(img, orientation), orientation


# ---------------------------------------------------------------------------
# Authentication
# ---------------------------------------------------------------------------

def _check_secret() -> bool:
    """Validate X-FACE-SERVICE-SECRET using constant-time comparison.

    Fails closed when FACE_SERVICE_SECRET is not set in the environment.
    """
    configured = os.getenv('FACE_SERVICE_SECRET')
    if not configured:
        return False
    provided = request.headers.get('X-FACE-SERVICE-SECRET', '')
    return hmac.compare_digest(provided, configured)


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.get('/health')
def health():
    return jsonify({
        'status': 'UP',
        'service': 'opencv-face-service',
        'anti_spoof': anti_spoof_engine.available,
        'landmarks': landmark_analyzer.available,
    })


@app.post('/extract-embedding')
def extract_embedding_route():
    """Authenticated: extract a face embedding with passive anti-spoofing.

    Response contract (preserved from production):
      Success: {"extracted": true, "is_real": true, "liveness_score": float,
                "embedding": [128 floats]}
      Rejected: {"extracted": false, "is_real": false,
                 "liveness_score": float, "rejection_reason": str, "reason": str}
      Error: {"extracted": false, "reason": str, "error": str}
    """
    if not _check_secret():
        return jsonify({'extracted': False, 'error': 'Invalid or missing service secret'}), 401

    file = request.files.get('image')
    if file is None or not file.filename:
        return jsonify({'extracted': False, 'reason': 'INVALID_IMAGE',
                        'error': 'Missing image field in request'}), 400

    raw = np.frombuffer(file.read(), np.uint8)
    if raw.size == 0:
        return jsonify({'extracted': False, 'reason': 'INVALID_IMAGE',
                        'error': 'Image file is empty'}), 400

    img, orientation = decode_image_upright(raw)
    if img is None:
        return jsonify({'extracted': False, 'reason': 'INVALID_IMAGE',
                        'error': 'Invalid or unreadable image format'}), 400

    try:
        # 1. Detect exactly one face via YuNet
        face_info = engine.detect_single_face_with_box(img)
    except FaceDetectionError as e:
        return jsonify({'extracted': False, 'reason': e.reason, 'error': str(e)}), 400
    except FaceError as e:
        return jsonify({'extracted': False, 'reason': 'PROCESSING', 'error': str(e)}), 400

    # 2. Passive anti-spoof: quality gate always runs; MiniFASNet ensemble is
    #    skipped when DISABLE_PASSIVE_ANTISPOOF=true in development mode only.
    #    See face_module/config.py:_resolve_disable_passive_antispoof() for the
    #    production guard: the flag is a no-op unless FLASK_ENV=development.
    if config.disable_passive_antispoof:
        # Quality gate still runs — blur/brightness/face-ratio checks are fast
        # and catch unusable images before SFace embedding extraction.
        # Compute metrics once, derive rejection reason from them (avoids
        # calling check_quality + quality_metrics which would compute twice).
        _diag_metrics = anti_spoof_engine.quality_metrics(img, face_info[:4])
        quality_failure = _decide_quality(_diag_metrics, config)
        if quality_failure is not None:
            # [DIAG] Temporary diagnostic — remove after troubleshooting.
            # Logs rejection reason + quality metrics + thresholds + dimensions.
            # No image bytes, embeddings, or personal data are logged.
            app.logger.warning(
                "[DIAG] Quality rejection in dev bypass: %s | "
                "laplacian_var=%.1f (threshold %.1f) | "
                "brightness=%.1f (range %.1f-%.1f) | "
                "face_ratio=%.5f (threshold %.5f) | "
                "face_box=[x=%d,y=%d,w=%d,h=%d] | "
                "image=[w=%d,h=%d]",
                quality_failure,
                _diag_metrics["laplacian_var"], config.blur_min_variance,
                _diag_metrics["brightness_mean"],
                config.min_brightness, config.max_brightness,
                _diag_metrics["face_ratio"], config.min_face_ratio,
                int(face_info[0]), int(face_info[1]),
                int(face_info[2]), int(face_info[3]),
                img.shape[1], img.shape[0],
            )
            return jsonify({
                'extracted': False,
                'is_real': False,
                'liveness_score': 0.0,
                'rejection_reason': quality_failure,
                'reason': 'QUALITY',
                'error': 'Face image quality check failed',
            }), 200
        app.logger.info(
            "[DEV] MiniFASNet passive anti-spoof bypassed "
            "(DISABLE_PASSIVE_ANTISPOOF=true, FLASK_ENV=development). "
            "Quality gate passed. SFace identity matching still required."
        )
        # Passive anti-spoof is explicitly SKIPPED — do not fabricate a score.
        # liveness_score is None (not 1.0). The passive_antispoof_status field
        # tells the caller the check was skipped. Active liveness challenges
        # and SFace identity matching still validate the session.
        liveness = {
            'is_real': True,
            'liveness_score': None,
            'passive_antispoof_status': 'SKIPPED',
        }
    else:
        liveness = anti_spoof_engine.assess(img, face_info[:4])

    if not liveness['is_real']:
        rr = liveness['rejection_reason']
        reason = ('QUALITY' if rr in ('BLURRY_IMAGE', 'UNDEREXPOSED_IMAGE',
                                       'OVEREXPOSED_IMAGE', 'FACE_TOO_SMALL')
                  else 'SPOOF')
        return jsonify({
            'extracted': False,
            'is_real': False,
            'liveness_score': liveness['liveness_score'],
            'rejection_reason': rr,
            'reason': reason,
            'error': 'Face liveness verification failed',
        }), 200

    # 3. Extract SFace 128-dim embedding (only after liveness passes)
    try:
        embedding = engine.extract_embedding(img, face_info)
    except FaceError as e:
        return jsonify({'extracted': False, 'reason': 'PROCESSING', 'error': str(e)}), 400

    resp = {
        'extracted': True,
        'is_real': True,
        'liveness_score': liveness['liveness_score'],
        'embedding': embedding.tolist(),
    }
    if liveness.get('passive_antispoof_status'):
        resp['passive_antispoof_status'] = liveness['passive_antispoof_status']
    return jsonify(resp), 200


@app.post('/active-liveness/process')
def active_liveness_process():
    """Authenticated: process one frame for a Spring-owned active-liveness session.

    Multipart form fields:
      - frame:       JPEG image
      - session_id:  Spring's opaque session ID
      - challenge_seq: JSON array, e.g. ["BLINK", "TURN_LEFT"]
      - frame_seq:   integer frame sequence from Spring

    Response: {"decision": "PENDING|RETRYABLE_FAILURE|FAILED|PASSED",
               "reason": str|null}

    The response never lets the client assert liveness succeeded — Spring's
    evaluator maps the decision to its own session state.
    """
    if not _check_secret():
        return jsonify({'error': 'Invalid or missing service secret'}), 401

    import json

    file = request.files.get('frame')
    if file is None or not file.filename:
        return jsonify({'decision': 'FAILED', 'reason': 'MISSING_FRAME'}), 400

    session_id = request.form.get('session_id')
    if not session_id:
        return jsonify({'decision': 'FAILED', 'reason': 'MISSING_SESSION_ID'}), 400

    challenge_seq_raw = request.form.get('challenge_seq', '[]')
    try:
        challenge_seq = json.loads(challenge_seq_raw)
        if not isinstance(challenge_seq, list):
            raise ValueError
    except (json.JSONDecodeError, ValueError):
        return jsonify({'decision': 'FAILED', 'reason': 'INVALID_CHALLENGE_SEQ'}), 400

    frame_seq_raw = request.form.get('frame_seq', '')
    try:
        frame_seq = int(frame_seq_raw)
    except ValueError:
        return jsonify({'decision': 'FAILED', 'reason': 'INVALID_FRAME_SEQ'}), 400

    raw = np.frombuffer(file.read(), np.uint8)
    if raw.size == 0:
        return jsonify({'decision': 'FAILED', 'reason': 'EMPTY_FRAME'}), 400

    img, _ = decode_image_upright(raw)
    if img is None:
        return jsonify({'decision': 'FAILED', 'reason': 'INVALID_IMAGE'}), 400

    decision, reason = liveness_service.process_frame(
        session_id, challenge_seq, frame_seq, img
    )
    return jsonify({'decision': decision.value, 'reason': reason}), 200


if __name__ == '__main__':
    debug_mode = os.getenv('FLASK_DEBUG', 'false').lower() == 'true'
    app.run(host='0.0.0.0', port=5001, debug=debug_mode)
