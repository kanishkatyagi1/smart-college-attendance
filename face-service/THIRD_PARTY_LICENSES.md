# Third-Party Licenses

## OpenCV models

- **YuNet** (`face_detection_yunet_2023mar.onnx`): OpenCV Zoo, Apache-2.0.
- **SFace** (`face_recognition_sface_2021dec.onnx`): OpenCV Zoo, Apache-2.0.

## MiniFASNet anti-spoofing models

- `2.7_80x80_MiniFASNetV2.onnx` and `4_0_0_80x80_MiniFASNetV1SE.onnx` are ONNX
  conversions of Minivision's Silent-Face-Anti-Spoofing project (Apache-2.0),
  obtained from serengil/deepface_models.  The upstream project is Apache-2.0;
  retain upstream attribution when redistributing these model files.

## MediaPipe

- **Face Landmarker** (`face_landmarker.task`): Google MediaPipe, Apache-2.0.
  Downloaded from Google MediaPipe model storage and used through the
  `mediapipe` Python package.

## Python packages

See `requirements.txt` for version-pinned dependencies.  All are open source
(Flask: BSD-3, OpenCV: Apache-2.0, NumPy: BSD-2, ONNX Runtime: MIT,
MediaPipe: Apache-2.0, pytest: MIT).

Model files are downloaded explicitly, remain local, and are excluded from Git.
