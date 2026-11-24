package com.college.attendance.service;

/** Lifecycle state for short-lived, server-owned active liveness sessions. */
public enum ActiveLivenessState {
    ACTIVE,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED
}
