package com.college.attendance.service;

import com.college.attendance.dto.FaceVerificationResult;
import com.college.attendance.entity.FaceEmbedding;
import com.college.attendance.entity.Student;
import com.college.attendance.repo.FaceEmbeddingRepository;
import com.college.attendance.util.EncryptionUtil;
import com.college.attendance.util.EncryptionUtil.Ciphertext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FaceVerificationService {

    private static final Logger log = LoggerFactory.getLogger(FaceVerificationService.class);

    public static final int EXPECTED_EMBEDDING_DIM = 128;
    private static final Pattern FLASK_REASON_PATTERN = Pattern.compile("\\\"reason\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");
    private static final Set<String> SAFE_IMAGE_REASONS = Set.of(
            "INVALID_IMAGE", "NO_FACE", "MULTIPLE_FACES", "PROCESSING");

    private final FaceEmbeddingRepository embeddingRepo;
    private final RestTemplate restTemplate;

    @Value("${face.service.url}")
    private String faceServiceUrl;

    @Value("${face.service.secret}")
    private String faceServiceSecret;

    @Value("${face.verification.similarity-threshold:0.60}")
    private double similarityThreshold;

    public FaceVerificationService(FaceEmbeddingRepository embeddingRepo, RestTemplate restTemplate) {
        this.embeddingRepo = embeddingRepo;
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    void validateConfig() {
        if (faceServiceSecret == null || faceServiceSecret.isBlank()) {
            throw new IllegalStateException(
                    "face.service.secret is not configured — set FACE_SERVICE_SECRET environment variable");
        }
        if (faceServiceUrl == null || faceServiceUrl.isBlank()) {
            throw new IllegalStateException("face.service.url is not configured");
        }
        if (similarityThreshold <= 0.0 || similarityThreshold > 1.0) {
            throw new IllegalStateException("face.verification.similarity-threshold must be in range (0.0, 1.0]");
        }
    }

    /**
     * Verifies an authenticated student's live face against their registered face embedding.
     *
     * <ol>
     *   <li>Retrieves the registered encrypted embedding for the given student from MySQL.</li>
     *   <li>Decrypts the embedding ciphertext, nonce, and authentication tag using AES-256-GCM.</li>
     *   <li>Calls Flask {@code /extract-embedding} to perform passive anti-spoofing and extract embedding.</li>
     *   <li>If spoof is detected, rejects immediately without computing cosine similarity.</li>
     *   <li>If liveness passes, computes cosine similarity between registered and live embeddings.</li>
     *   <li>Compares similarity against the configured threshold.</li>
     * </ol>
     *
     * @param student   the authenticated student
     * @param liveImage raw bytes of the captured live face image
     * @return {@link FaceVerificationResult} indicating match status, similarity, and liveness
     */
    public FaceVerificationResult verifyStudentFace(Student student, byte[] liveImage) {
        if (student == null || student.getId() == null) {
            throw new IllegalArgumentException("Student must be valid and non-null");
        }
        if (liveImage == null || liveImage.length == 0) {
            throw new IllegalArgumentException("Live image bytes must not be empty");
        }

        // 1. Retrieve registered face embedding for the authenticated student only
        Optional<FaceEmbedding> embeddingOpt = embeddingRepo.findByStudent_Id(student.getId());
        if (embeddingOpt.isEmpty()) {
            log.info("Face verification requested for student id={} with no registered face", student.getId());
            return FaceVerificationResult.notRegistered();
        }

        FaceEmbedding fe = embeddingOpt.get();

        // 2. Decrypt registered embedding
        float[] registeredEmbedding;
        try {
            Ciphertext ct = new Ciphertext(fe.getNonce(), fe.getCiphertext(), fe.getTag());
            byte[] decryptedBytes = EncryptionUtil.decrypt(ct);
            registeredEmbedding = bytesToFloatArray(decryptedBytes);
        } catch (Exception e) {
            log.error("Failed to decrypt registered face embedding for student id={}", student.getId());
            throw new IllegalStateException("Failed to decrypt registered face embedding", e);
        }

        // 3. Extract live embedding via authenticated Flask /extract-embedding with anti-spoofing
        Map<String, Object> respBody = callFlaskExtract(liveImage);
        if (respBody == null) {
            log.error("Flask response body is null for student id={}", student.getId());
            throw new IllegalStateException("Empty response body from face service");
        }

        Object isRealObj = respBody.get("is_real");
        Object extractedObj = respBody.get("extracted");
        Object rejectionReasonObj = respBody.get("rejection_reason");
        Object responseScore = respBody.get("liveness_score");
        log.info("Face service decision: is_real={}, extracted={}, liveness_score={}, rejection_reason={}",
                isRealObj, extractedObj, responseScore, rejectionReasonObj);

        // Case 2: Explicit spoof detection (is_real == false) -> presentation attack rejected
        if (Boolean.FALSE.equals(isRealObj)) {
            double livenessScore = respBody.get("liveness_score") instanceof Number
                    ? ((Number) respBody.get("liveness_score")).doubleValue() : 0.0;
            String reason = respBody.get("rejection_reason") instanceof String
                    ? (String) respBody.get("rejection_reason") : "SPOOF_DETECTED";
            log.warn("Face liveness verification failed for student id={}, score={}, reason={}",
                    student.getId(), livenessScore, reason);
            return FaceVerificationResult.spoofDetected(livenessScore, reason);
        }

        // Cases 3 & 4: is_real missing, null, or non-boolean -> fail closed immediately
        if (!Boolean.TRUE.equals(isRealObj)) {
            log.warn("Face service rejection: invalid is_real decision; fail closed");
            log.error("Flask response missing valid boolean 'is_real' field (received: {}) for student id={}",
                    isRealObj, student.getId());
            throw new IllegalStateException("Malformed face service response: is_real must be explicitly boolean");
        }

        // Case 6: Validate liveness_score strictly when is_real == true.
        // When passive_antispoof_status == "SKIPPED" (development-only
        // DISABLE_PASSIVE_ANTISPOOF bypass), liveness_score is null — we do
        // not fabricate a score. Active liveness challenges and SFace identity
        // matching still validated the session. Production never sends
        // passive_antispoof_status, so the strict validation below is unchanged.
        Object liveScoreObj = respBody.get("liveness_score");
        Object antispoofStatus = respBody.get("passive_antispoof_status");
        double livenessScore;
        if ("SKIPPED".equals(antispoofStatus)) {
            log.info("Passive anti-spoof SKIPPED (development bypass) for student id={}", student.getId());
            livenessScore = 0.0;
        } else {
            if (!(liveScoreObj instanceof Number)) {
                log.error("Flask response missing numeric 'liveness_score' for student id={}", student.getId());
                throw new IllegalStateException("Malformed face service response: missing or invalid liveness_score");
            }
            livenessScore = ((Number) liveScoreObj).doubleValue();
            if (Double.isNaN(livenessScore) || Double.isInfinite(livenessScore) || livenessScore < 0.0 || livenessScore > 1.0) {
                log.error("Flask response contains non-finite or out-of-range liveness_score: {} for student id={}",
                        livenessScore, student.getId());
                throw new IllegalStateException("Malformed face service response: liveness_score must be a finite number between 0.0 and 1.0");
            }
        }

        // Ensure face was successfully extracted
        if (!Boolean.TRUE.equals(respBody.get("extracted"))) {
            log.warn("Face service rejection: is_real=true but extracted is not true");
            throw new IllegalStateException("Face extraction unsuccessful");
        }

        // Validate embedding format
        Object embObj = respBody.get("embedding");
        if (!(embObj instanceof List)) {
            log.warn("Face service rejection: extracted=true but embedding field is missing or invalid");
            throw new IllegalStateException("Invalid embedding format from face service");
        }
        @SuppressWarnings("unchecked")
        List<Number> liveList = (List<Number>) embObj;
        float[] liveEmbedding = toFloatArray(liveList);

        // 4. Compute cosine similarity
        log.info("Face matching reached: computing cosine similarity");
        double similarity = cosineSimilarity(registeredEmbedding, liveEmbedding);
        boolean matched = similarity >= similarityThreshold;

        log.info("Face matching result: matched={}, cosineSimilarity={}, similarityThreshold={}, livenessScore={}",
                matched, similarity, similarityThreshold, livenessScore);
        return matched
                ? FaceVerificationResult.liveMatched(similarity, livenessScore)
                : FaceVerificationResult.liveNotMatched(similarity, livenessScore);
    }

    /**
     * POST image to Flask as multipart/form-data with field name {@code image}.
     * Header {@code X-FACE-SERVICE-SECRET} is transmitted for service-to-service authentication.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callFlaskExtract(byte[] imageBytes) {
        String url = faceServiceUrl + "/extract-embedding";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return "face.jpg";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-FACE-SERVICE-SECRET", faceServiceSecret);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.POST, request, Map.class);
            Map<String, Object> respBody = resp.getBody();
            if (respBody == null) {
                throw new IllegalStateException("Empty response body from face service");
            }
            return respBody;
        } catch (HttpClientErrorException e) {
            log.warn("Flask /extract-embedding returned HTTP {}", e.getStatusCode().value());
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new FaceProcessingException(extractSafeReason(e.getResponseBodyAsString()));
            }
            throw new IllegalArgumentException("Face image could not be processed");
        } catch (HttpServerErrorException e) {
            log.error("Flask /extract-embedding returned HTTP 5xx: {}", e.getStatusCode().value());
            throw e;
        } catch (ResourceAccessException e) {
            log.error("Flask face service unreachable during verification", e);
            throw e;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error calling face service during verification", e);
            throw new IllegalStateException("Unexpected error calling face service", e);
        }
    }

    private static String extractSafeReason(String responseBody) {
        Matcher matcher = FLASK_REASON_PATTERN.matcher(responseBody == null ? "" : responseBody);
        if (matcher.find() && SAFE_IMAGE_REASONS.contains(matcher.group(1))) {
            return matcher.group(1);
        }
        return "INVALID_IMAGE";
    }

    /**
     * Converts a 512-byte IEEE-754 array to 128 float values.
     */
    public static float[] bytesToFloatArray(byte[] bytes) {
        if (bytes == null || bytes.length != EXPECTED_EMBEDDING_DIM * 4) {
            throw new IllegalArgumentException("Expected exactly " + (EXPECTED_EMBEDDING_DIM * 4) + " bytes for 128-dim embedding");
        }
        float[] arr = new float[EXPECTED_EMBEDDING_DIM];
        for (int i = 0; i < EXPECTED_EMBEDDING_DIM; i++) {
            int bits = ((bytes[i * 4] & 0xFF) << 24)
                     | ((bytes[i * 4 + 1] & 0xFF) << 16)
                     | ((bytes[i * 4 + 2] & 0xFF) << 8)
                     | (bytes[i * 4 + 3] & 0xFF);
            float val = Float.intBitsToFloat(bits);
            if (Float.isNaN(val) || Float.isInfinite(val)) {
                throw new IllegalArgumentException("Decrypted embedding contains non-finite element at index " + i);
            }
            arr[i] = val;
        }
        return arr;
    }

    /**
     * Converts a List of Numbers to a 128 float array with finite-value validation.
     */
    public static float[] toFloatArray(List<Number> list) {
        if (list == null || list.size() != EXPECTED_EMBEDDING_DIM) {
            throw new IllegalArgumentException("Expected " + EXPECTED_EMBEDDING_DIM + "-element embedding list");
        }
        float[] arr = new float[EXPECTED_EMBEDDING_DIM];
        for (int i = 0; i < EXPECTED_EMBEDDING_DIM; i++) {
            Number n = list.get(i);
            if (n == null) {
                throw new IllegalArgumentException("Embedding contains null element at index " + i);
            }
            float val = n.floatValue();
            if (Float.isNaN(val) || Float.isInfinite(val)) {
                throw new IllegalArgumentException("Embedding contains non-finite element at index " + i);
            }
            arr[i] = val;
        }
        return arr;
    }

    /**
     * Computes cosine similarity between two 128-dimensional vectors.
     *
     * <p>cosine_similarity(A, B) = (A · B) / (||A||_2 * ||B||_2)
     *
     * @param v1 first vector (e.g. registered embedding)
     * @param v2 second vector (e.g. live embedding)
     * @return similarity score in range [-1.0, 1.0]
     * @throws IllegalArgumentException if lengths != 128, elements are NaN/Infinite,
     *                                  or either vector has zero norm.
     */
    public static double cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null) {
            throw new IllegalArgumentException("Vectors must not be null");
        }
        if (v1.length != EXPECTED_EMBEDDING_DIM || v2.length != EXPECTED_EMBEDDING_DIM) {
            throw new IllegalArgumentException("Expected " + EXPECTED_EMBEDDING_DIM + "-dimensional vectors");
        }

        double dotProduct = 0.0;
        double norm1Sq = 0.0;
        double norm2Sq = 0.0;

        for (int i = 0; i < EXPECTED_EMBEDDING_DIM; i++) {
            float a = v1[i];
            float b = v2[i];

            if (Float.isNaN(a) || Float.isInfinite(a) || Float.isNaN(b) || Float.isInfinite(b)) {
                throw new IllegalArgumentException("Vector elements must be finite numbers");
            }

            dotProduct += (double) a * (double) b;
            norm1Sq += (double) a * (double) a;
            norm2Sq += (double) b * (double) b;
        }

        double norm1 = Math.sqrt(norm1Sq);
        double norm2 = Math.sqrt(norm2Sq);

        if (norm1 < 1e-12 || norm2 < 1e-12) {
            throw new IllegalArgumentException("Vector norm must be strictly positive (zero vector detected)");
        }

        double sim = dotProduct / (norm1 * norm2);
        return Math.max(-1.0, Math.min(1.0, sim));
    }
}
