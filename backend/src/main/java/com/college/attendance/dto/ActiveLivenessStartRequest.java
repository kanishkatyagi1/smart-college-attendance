package com.college.attendance.dto;

import com.college.attendance.service.ActiveLivenessPurpose;

/** Client input deliberately excludes student identity and challenge outcome. */
public record ActiveLivenessStartRequest(ActiveLivenessPurpose purpose, String attemptId) {}
