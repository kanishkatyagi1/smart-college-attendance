"""Temporal eyeglasses quality gate derived from MediaPipe eye landmarks.

Carried over unchanged from the prototype.  Uses separate detected/clear
confirmation windows to prevent state flapping.  The temporal gate makes this
only a candidate signal — an eyebrow, shadow, or single reflection is not
enough to trigger a glasses rejection.
"""

class GlassesGate:
    """Use separate detected/clear confirmation windows to prevent state flapping."""

    def __init__(self, confirmations=3, clear_confirmations=4, detected_threshold=.72, clear_threshold=.28):
        self.confirmations = confirmations
        self.clear_confirmations = clear_confirmations
        self.detected_threshold = detected_threshold
        self.clear_threshold = clear_threshold
        self.state = "glasses_uncertain"
        self.detected_confirm_count = 0
        self.clear_confirm_count = 0
        self.last_score = 0.0
        self.last_eye_region_quality = 0.0
        self.last_bridge_region_score = 0.0

    @property
    def confidence(self):
        return round(self.last_score, 3)

    @property
    def temporal_confirm_count(self):
        return max(self.detected_confirm_count, self.clear_confirm_count)

    @property
    def diagnostics(self):
        """Numerical-only diagnostics; no images, embeddings, or identity data."""
        return {
            "glasses_score": round(self.last_score, 3),
            "temporal_confirm_count": self.temporal_confirm_count,
            "eye_region_quality": round(self.last_eye_region_quality, 3),
            "bridge_region_score": round(self.last_bridge_region_score, 3),
        }

    def observe(self, score, eye_region_quality=0.0, bridge_region_score=0.0):
        self.last_score = max(0.0, min(1.0, float(score)))
        self.last_eye_region_quality = max(0.0, min(1.0, float(eye_region_quality)))
        self.last_bridge_region_score = max(0.0, min(1.0, float(bridge_region_score)))

        if self.last_score >= self.detected_threshold:
            self.detected_confirm_count += 1
            self.clear_confirm_count = 0
            if self.detected_confirm_count >= self.confirmations:
                self.state = "glasses_detected"
        elif self.last_score <= self.clear_threshold:
            self.clear_confirm_count += 1
            self.detected_confirm_count = 0
            if self.clear_confirm_count >= self.clear_confirmations:
                self.state = "glasses_clear"
        else:
            # Preserve confirmed state inside the hysteresis band rather than
            # switching clear/detected/clear around a noisy frame.
            self.detected_confirm_count = 0
            self.clear_confirm_count = 0
        return self.state
