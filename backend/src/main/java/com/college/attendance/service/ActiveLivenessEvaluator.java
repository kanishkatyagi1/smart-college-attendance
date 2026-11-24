package com.college.attendance.service;

import java.util.List;

/**
 * Internal boundary for the future authenticated Spring-to-Flask active-liveness
 * client. Phase 1 has no MediaPipe or Flask active endpoint; its default
 * implementation always leaves the session pending.
 */
public interface ActiveLivenessEvaluator {
    Evaluation evaluate(String internalSessionId, List<ActiveLivenessAction> challenge,
                        long sequence, byte[] frameBytes, String contentType);

    enum Decision { PENDING, RETRYABLE_FAILURE, FAILED, PASSED }

    record Evaluation(Decision decision, String safeReason) {
        public static Evaluation pending() { return new Evaluation(Decision.PENDING, null); }
    }
}
