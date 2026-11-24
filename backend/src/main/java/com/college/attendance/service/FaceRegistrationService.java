package com.college.attendance.service;

import com.college.attendance.entity.FaceEmbedding;
import com.college.attendance.entity.Student;
import com.college.attendance.repo.FaceEmbeddingRepository;
import com.college.attendance.repo.StudentRepository;
import com.college.attendance.repo.UserRepository;
import com.college.attendance.util.EncryptionUtil;
import com.college.attendance.util.EncryptionUtil.Ciphertext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FaceRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(FaceRegistrationService.class);

    /** SFace produces a 128-dimensional embedding. Reject anything else. */
    private static final int EXPECTED_EMBEDDING_DIM = 128;
    private static final Pattern FLASK_REASON_PATTERN = Pattern.compile("\\\"reason\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");
    private static final Set<String> SAFE_IMAGE_REASONS = Set.of(
            "INVALID_IMAGE", "NO_FACE", "MULTIPLE_FACES", "PROCESSING");

    private final StudentRepository studentRepo;
    private final FaceEmbeddingRepository embeddingRepo;
    private final UserRepository userRepo;
    private final RestTemplate restTemplate;

    @Value("${face.service.url}")
    private String faceServiceUrl;

    @Value("${face.service.secret}")
    private String faceServiceSecret;

    // ── Production constructor – RestTemplate is the managed Spring bean ──────
    public FaceRegistrationService(StudentRepository studentRepo,
                                   FaceEmbeddingRepository embeddingRepo,
                                   UserRepository userRepo,
                                   RestTemplate restTemplate) {
        this.studentRepo   = studentRepo;
        this.embeddingRepo = embeddingRepo;
        this.userRepo      = userRepo;
        this.restTemplate  = restTemplate;
    }

    /**
     * Validates that mandatory configuration values are present at application startup.
     * Throws {@link IllegalStateException} — which Spring translates to a startup
     * failure — if either value is missing or blank.
     * The secret value is intentionally never included in any log message or exception message.
     */
    @PostConstruct
    void validateConfig() {
        if (faceServiceSecret == null || faceServiceSecret.isBlank()) {
            throw new IllegalStateException(
                    "face.service.secret is not configured — set the FACE_SERVICE_SECRET environment variable");
        }
        if (faceServiceUrl == null || faceServiceUrl.isBlank()) {
            throw new IllegalStateException(
                    "face.service.url is not configured");
        }
    }

    /**
     * Register a student's face embedding.
     *
     * <p>Sends the image as {@code multipart/form-data} to Flask
     * {@code POST /extract-embedding}, validates the response, encrypts the
     * 128-dim embedding with AES-256-GCM, and persists only the ciphertext.
     *
     * <p>The original image bytes and the plaintext embedding are never persisted.
     *
     * @param userEmail  authenticated student's email (from JWT principal)
     * @param imageBytes raw image bytes from the uploaded file
     */
    @Transactional
    public void registerFace(String userEmail, byte[] imageBytes) {

        // ── 1. Resolve authenticated user → student ───────────────────────────
        var userOpt = userRepo.findByEmail(userEmail);
        var student = userOpt.flatMap(u -> studentRepo.findByUser(u))
                .orElseThrow(() ->
                        new IllegalStateException("Authenticated user not linked to a student"));

        // ── 2. Active / approved guard ────────────────────────────────────────
        if (!student.getUser().isActive()
                || student.getUser().getApprovalStatus()
                        != com.college.attendance.entity.ApprovalStatus.APPROVED) {
            throw new IllegalStateException("Student not active or not approved");
        }

        // ── 3. Duplicate guard ────────────────────────────────────────────────
        if (embeddingRepo.existsByStudent_Id(student.getId())) {
            throw new IllegalStateException("Face already registered");
        }

        // ── 4. Call Flask /extract-embedding as multipart/form-data ──────────
        List<Number> embeddingList = callFlaskExtract(imageBytes);

        // ── 5. Validate embedding ─────────────────────────────────────────────
        validateEmbedding(embeddingList);

        // ── 6. Encode embedding to IEEE-754 bytes and encrypt ─────────────────
        byte[] plain = embeddingToBytes(embeddingList);
        Ciphertext cipher = encryptEmbedding(plain);

        // ── 7. Persist ciphertext only (never the image or plaintext) ─────────
        FaceEmbedding fe = FaceEmbedding.builder()
                .student(student)
                .nonce(cipher.nonce)
                .ciphertext(cipher.ciphertext)
                .tag(cipher.tag)
                .encryptionVersion("v1")
                .build();
        embeddingRepo.save(fe);
        log.info("Face embedding persisted for student id={}", student.getId());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * POST image to Flask as {@code multipart/form-data} with field name {@code image}.
     * Header {@code X-FACE-SERVICE-SECRET} is set from configuration.
     *
     * @return the raw embedding list from the Flask JSON response
     */
    @SuppressWarnings("unchecked")
    private List<Number> callFlaskExtract(byte[] imageBytes) {
        String url = faceServiceUrl + "/extract-embedding";

        // Build multipart body: field "image" with the raw bytes
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() { return "face.jpg"; }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        // Secret is from server config – never logged
        headers.set("X-FACE-SERVICE-SECRET", faceServiceSecret);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        Map<String, Object> respBody;
        try {
            ResponseEntity<Map> resp =
                    restTemplate.exchange(url, HttpMethod.POST, request, Map.class);
            respBody = resp.getBody();
        } catch (HttpClientErrorException e) {
            // 400 = no face / multiple faces / bad image; 401 = secret mismatch.
            // S-4: Log the HTTP status for diagnostics, but do NOT include it in the
            // exception message — the message propagates to the Android client and
            // must not reveal internal status codes or configuration details.
            log.warn("Flask /extract-embedding returned HTTP {}", e.getStatusCode().value());
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new FaceProcessingException(extractSafeReason(e.getResponseBodyAsString()));
            }
            throw new IllegalArgumentException("Face image could not be processed");
        } catch (ResourceAccessException e) {
            log.error("Flask face service unreachable", e);
            throw new IllegalStateException("Face service unavailable", e);
        } catch (Exception e) {
            log.error("Unexpected error calling face service", e);
            throw new RuntimeException("Unexpected error calling face service", e);
        }

        // Check "extracted": true in response body
        if (respBody == null || !Boolean.TRUE.equals(respBody.get("extracted"))) {
            log.warn("Flask response did not contain extracted=true");
            throw new IllegalStateException("Face extraction unsuccessful");
        }

        Object embObj = respBody.get("embedding");
        if (!(embObj instanceof List)) {
            throw new IllegalStateException("Invalid embedding format from face service");
        }
        return (List<Number>) embObj;
    }

    private static String extractSafeReason(String responseBody) {
        Matcher matcher = FLASK_REASON_PATTERN.matcher(responseBody == null ? "" : responseBody);
        if (matcher.find() && SAFE_IMAGE_REASONS.contains(matcher.group(1))) {
            return matcher.group(1);
        }
        return "INVALID_IMAGE";
    }

    /** Validate that the embedding is non-null, non-empty, correct dimension, all numeric. */
    private void validateEmbedding(List<Number> embeddingList) {
        if (embeddingList == null || embeddingList.isEmpty()) {
            throw new IllegalStateException("Empty embedding received from face service");
        }
        if (embeddingList.size() != EXPECTED_EMBEDDING_DIM) {
            throw new IllegalStateException(
                    "Unexpected embedding dimension: expected " + EXPECTED_EMBEDDING_DIM
                            + ", got " + embeddingList.size());
        }
        for (Number n : embeddingList) {
            if (n == null) {
                throw new IllegalStateException("Embedding contains null value");
            }
            double v = n.doubleValue();
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalStateException("Embedding contains non-finite value");
            }
        }
    }

    /** Convert a list of embedding floats to a big-endian IEEE-754 byte array. */
    private byte[] embeddingToBytes(List<Number> embeddingList) {
        byte[] plain = new byte[embeddingList.size() * 4];
        for (int i = 0; i < embeddingList.size(); i++) {
            int bits = Float.floatToIntBits(embeddingList.get(i).floatValue());
            plain[i * 4]     = (byte) (bits >>> 24);
            plain[i * 4 + 1] = (byte) (bits >>> 16);
            plain[i * 4 + 2] = (byte) (bits >>> 8);
            plain[i * 4 + 3] = (byte)  bits;
        }
        return plain;
    }

    private Ciphertext encryptEmbedding(byte[] plain) {
        try {
            return EncryptionUtil.encrypt(plain);
        } catch (Exception e) {
            log.error("Encryption failed");
            throw new IllegalStateException("Encryption error", e);
        }
    }
}
