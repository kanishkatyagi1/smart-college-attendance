package com.college.attendance.controller;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Structured JSON response for POST /api/face/register.
 *
 * <p>Jackson serialises this automatically — no manual string building.
 * Fields with a {@code null} value are omitted from the JSON output.
 *
 * <p>Android {@code FaceCaptureActivity} checks {@code result.registered}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FaceRegistrationResponse {

    private final boolean registered;
    private final String  message;
    private final String  error;

    private FaceRegistrationResponse(boolean registered, String message, String error) {
        this.registered = registered;
        this.message    = message;
        this.error      = error;
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    /** Success: {@code {"registered":true,"message":"…"}} */
    public static FaceRegistrationResponse success(String message) {
        return new FaceRegistrationResponse(true, message, null);
    }

    /** Failure: {@code {"registered":false,"error":"…"}} */
    public static FaceRegistrationResponse failure(String error) {
        return new FaceRegistrationResponse(false, null, error);
    }

    // ── Accessors (Jackson needs getters) ─────────────────────────────────────

    public boolean isRegistered() { return registered; }
    public String  getMessage()   { return message; }
    public String  getError()     { return error; }
}
