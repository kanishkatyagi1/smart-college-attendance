package com.college.attendance.service;

/**
 * A safe, allowlisted image-processing rejection returned by the internal face
 * service. It carries no image, embedding, credential, or model detail.
 */
public class FaceProcessingException extends IllegalArgumentException {

    private final String reason;

    public FaceProcessingException(String reason) {
        super("Face image could not be processed");
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
