package com.college.attendance.dto;

/**
 * Result of biometric face verification and anti-spoofing check.
 * Contains match status, similarity score, liveness status, and liveness score.
 * Never exposes raw embeddings or vectors.
 */
public class FaceVerificationResult {

    private final boolean faceRegistered;
    private final boolean matched;
    private final double similarity;
    private final boolean live;
    private final double livenessScore;
    private final String rejectionReason;

    private FaceVerificationResult(boolean faceRegistered, boolean matched, double similarity,
                                   boolean live, double livenessScore, String rejectionReason) {
        this.faceRegistered = faceRegistered;
        this.matched = matched;
        this.similarity = similarity;
        this.live = live;
        this.livenessScore = livenessScore;
        this.rejectionReason = rejectionReason;
    }

    public static FaceVerificationResult notRegistered() {
        return new FaceVerificationResult(false, false, 0.0, false, 0.0, "NOT_REGISTERED");
    }

    public static FaceVerificationResult of(boolean matched, double similarity) {
        return new FaceVerificationResult(true, matched, similarity, true, 1.0, null);
    }

    public static FaceVerificationResult liveMatched(double similarity, double livenessScore) {
        return new FaceVerificationResult(true, true, similarity, true, livenessScore, null);
    }

    public static FaceVerificationResult liveNotMatched(double similarity, double livenessScore) {
        return new FaceVerificationResult(true, false, similarity, true, livenessScore, "FACE_NOT_MATCHED");
    }

    public static FaceVerificationResult spoofDetected(double livenessScore, String rejectionReason) {
        return new FaceVerificationResult(true, false, 0.0, false, livenessScore, rejectionReason);
    }

    public boolean isFaceRegistered() {
        return faceRegistered;
    }

    public boolean isMatched() {
        return matched;
    }

    public double getSimilarity() {
        return similarity;
    }

    public boolean isLive() {
        return live;
    }

    public double getLivenessScore() {
        return livenessScore;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }
}
