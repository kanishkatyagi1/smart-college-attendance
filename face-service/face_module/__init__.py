"""Face-service core package: detection, recognition, anti-spoofing, and active liveness.

This package is the clean foundation adapted from the standalone prototype.
It owns no storage, no Flask routes, and no request handling.  Flask (``app.py``)
remains the HTTP boundary; Spring Boot remains the storage and attendance boundary.
"""
