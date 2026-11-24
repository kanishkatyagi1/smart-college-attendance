package com.college.attendance.dto;

import com.college.attendance.service.ActiveLivenessAction;
import com.college.attendance.service.ActiveLivenessPurpose;
import com.college.attendance.service.ActiveLivenessState;

import java.time.Instant;
import java.util.List;

/** Safe session view. It never contains biometric data, service secrets or embeddings. */
public record ActiveLivenessSessionResponse(
        String sessionId,
        ActiveLivenessPurpose purpose,
        ActiveLivenessState state,
        List<ActiveLivenessAction> challenge,
        Instant expiresAt,
        int retryCount,
        long nextExpectedSequence,
        String reason) {}
