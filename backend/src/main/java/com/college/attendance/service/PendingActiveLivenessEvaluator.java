package com.college.attendance.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase-1 fail-closed placeholder. It deliberately cannot authenticate a
 * challenge and therefore cannot produce a completion that affects attendance.
 */
@Component
public class PendingActiveLivenessEvaluator implements ActiveLivenessEvaluator {
    @Override
    public Evaluation evaluate(String internalSessionId, List<ActiveLivenessAction> challenge,
                               long sequence, byte[] frameBytes, String contentType) {
        return Evaluation.pending();
    }
}
