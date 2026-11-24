package com.college.attendance.service;

import com.college.attendance.dto.FaceVerificationResult;
import com.college.attendance.entity.FaceEmbedding;
import com.college.attendance.entity.Student;
import com.college.attendance.repo.FaceEmbeddingRepository;
import com.college.attendance.util.EncryptionUtil;
import com.college.attendance.util.EncryptionUtil.Ciphertext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FaceVerificationServiceTest {

    @Mock private FaceEmbeddingRepository embeddingRepo;
    @Mock private RestTemplate restTemplate;

    private FaceVerificationService service;
    private Student student;
    private Student otherStudent;

    private static final double TEST_THRESHOLD = 0.60;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        System.setProperty("face.emb.key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        System.setProperty("face.emb.key.useEnv", "false");

        student = Student.builder().id(101L).build();
        otherStudent = Student.builder().id(202L).build();

        service = new FaceVerificationService(embeddingRepo, restTemplate);
        setField(service, "faceServiceUrl", "http://localhost:5001");
        setField(service, "faceServiceSecret", "test-secret");
        setField(service, "similarityThreshold", TEST_THRESHOLD);
        service.validateConfig();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("face.emb.key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        System.setProperty("face.emb.key.useEnv", "false");
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("setField failed for " + fieldName, e);
        }
    }

    private static byte[] floatsToBytes(float[] floats) {
        byte[] bytes = new byte[floats.length * 4];
        for (int i = 0; i < floats.length; i++) {
            int bits = Float.floatToIntBits(floats[i]);
            bytes[i * 4]     = (byte) (bits >>> 24);
            bytes[i * 4 + 1] = (byte) (bits >>> 16);
            bytes[i * 4 + 2] = (byte) (bits >>> 8);
            bytes[i * 4 + 3] = (byte) bits;
        }
        return bytes;
    }

    private static List<Number> floatsToList(float[] floats) {
        List<Number> list = new ArrayList<>(floats.length);
        for (float f : floats) {
            list.add(f);
        }
        return list;
    }

    private FaceEmbedding createEncryptedEmbedding(Student s, float[] vector) throws Exception {
        byte[] plain = floatsToBytes(vector);
        Ciphertext ct = EncryptionUtil.encrypt(plain);
        return FaceEmbedding.builder()
                .student(s)
                .nonce(ct.nonce)
                .ciphertext(ct.ciphertext)
                .tag(ct.tag)
                .encryptionVersion("v1")
                .build();
    }

    private void stubFlaskSuccess(List<Number> embedding) {
        Map<String, Object> body = Map.of(
                "extracted", true,
                "is_real", true,
                "liveness_score", 0.95,
                "embedding", embedding
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    /** Stub a Flask response where passive anti-spoof was SKIPPED (dev bypass).
     *  liveness_score is null — Map.of() rejects nulls, so use HashMap. */
    @SuppressWarnings("unchecked")
    private void stubFlaskSuccessSkipped(List<Number> embedding) {
        Map<String, Object> body = new HashMap<>();
        body.put("extracted", true);
        body.put("is_real", true);
        body.put("liveness_score", null);
        body.put("passive_antispoof_status", "SKIPPED");
        body.put("embedding", embedding);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    // ── 1. Genuine face match ────────────────────────────────────────────────

    @Test
    void successfulGenuineFaceMatchAboveThreshold() throws Exception {
        float[] registeredVector = new float[128];
        Arrays.fill(registeredVector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, registeredVector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        // Live vector identical to registered vector -> similarity = 1.0 (well above 0.60)
        float[] liveVector = Arrays.copyOf(registeredVector, 128);
        stubFlaskSuccess(floatsToList(liveVector));

        FaceVerificationResult result = service.verifyStudentFace(student, new byte[]{1, 2, 3});

        assertTrue(result.isFaceRegistered(), "Face must be registered");
        assertTrue(result.isMatched(), "Genuine face should match");
        assertEquals(1.0, result.getSimilarity(), 0.001, "Identical vectors must have cosine similarity of 1.0");
    }

    // ── 1b. Genuine face match with passive anti-spoof SKIPPED (dev bypass) ──

    @Test
    void successfulMatchWhenPassiveAntispoofSkipped() throws Exception {
        float[] registeredVector = new float[128];
        Arrays.fill(registeredVector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, registeredVector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        float[] liveVector = Arrays.copyOf(registeredVector, 128);
        stubFlaskSuccessSkipped(floatsToList(liveVector));

        FaceVerificationResult result = service.verifyStudentFace(student, new byte[]{1, 2, 3});

        assertTrue(result.isFaceRegistered(), "Face must be registered");
        assertTrue(result.isMatched(), "Genuine face should match even with antispoof skipped");
        assertEquals(1.0, result.getSimilarity(), 0.001, "SFace identity matching must still run");
    }

    // ── 2. Different face mismatch ───────────────────────────────────────────

    @Test
    void differentFaceMismatchBelowThreshold() throws Exception {
        float[] registeredVector = new float[128];
        Arrays.fill(registeredVector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, registeredVector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        // Live vector orthogonal or inverted -> similarity well below 0.60
        float[] liveVector = new float[128];
        for (int i = 0; i < 128; i++) {
            liveVector[i] = (i % 2 == 0) ? 0.1f : -0.1f;
        }
        stubFlaskSuccess(floatsToList(liveVector));

        FaceVerificationResult result = service.verifyStudentFace(student, new byte[]{1, 2, 3});

        assertTrue(result.isFaceRegistered());
        assertFalse(result.isMatched(), "Different face must not match");
        assertTrue(result.getSimilarity() < TEST_THRESHOLD, "Similarity must be below configured threshold");
    }

    // ── 3. Missing registered embedding ──────────────────────────────────────

    @Test
    void missingRegisteredEmbeddingReturnsNotRegistered() {
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.empty());

        FaceVerificationResult result = service.verifyStudentFace(student, new byte[]{1, 2, 3});

        assertFalse(result.isFaceRegistered(), "Must report face not registered");
        assertFalse(result.isMatched(), "Unregistered face must not match");
        assertEquals(0.0, result.getSimilarity());
        verifyNoInteractions(restTemplate);
    }

    // ── 4. Wrong student isolation ───────────────────────────────────────────

    @Test
    void wrongStudentCannotAccessAnotherStudentsEmbedding() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding feOther = createEncryptedEmbedding(otherStudent, vector);

        // Repo only has otherStudent's embedding
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.empty());
        when(embeddingRepo.findByStudent_Id(otherStudent.getId())).thenReturn(Optional.of(feOther));

        FaceVerificationResult result = service.verifyStudentFace(student, new byte[]{1, 2, 3});

        assertFalse(result.isFaceRegistered(), "Student 101 must not access Student 202's embedding");
        verify(embeddingRepo).findByStudent_Id(student.getId());
        verify(embeddingRepo, never()).findByStudent_Id(otherStudent.getId());
    }

    // ── 5. Threshold boundaries ──────────────────────────────────────────────

    @Test
    void thresholdBoundaryExactMatch() {
        float[] v1 = new float[128];
        float[] v2 = new float[128];
        v1[0] = 1.0f;
        v2[0] = 0.60f;
        v2[1] = (float) Math.sqrt(1.0 - 0.60 * 0.60);

        double similarity = FaceVerificationService.cosineSimilarity(v1, v2);
        assertEquals(0.60, similarity, 0.001);
        assertTrue(similarity >= TEST_THRESHOLD, "Exact threshold match meets threshold");
    }

    @Test
    void thresholdBoundaryJustBelowIsMismatch() {
        float[] v1 = new float[128];
        float[] v2 = new float[128];
        v1[0] = 1.0f;
        v2[0] = 0.599f;
        v2[1] = (float) Math.sqrt(1.0 - 0.599 * 0.599);

        double similarity = FaceVerificationService.cosineSimilarity(v1, v2);
        assertEquals(0.599, similarity, 0.001);
        assertFalse(similarity >= TEST_THRESHOLD, "Just below threshold must be rejected as mismatch");
    }

    @Test
    void cosineSimilarityKnownAngle() {
        float[] v1 = new float[128];
        float[] v2 = new float[128];
        v1[0] = 1.0f;
        v2[0] = 0.5f;
        v2[1] = (float) Math.sqrt(0.75); // 60-degree angle, cos(60) = 0.50

        double similarity = FaceVerificationService.cosineSimilarity(v1, v2);
        assertEquals(0.50, similarity, 0.001);
    }

    // ── 6. Mathematical validation & zero vector ─────────────────────────────

    @Test
    void zeroVectorNormThrows() {
        float[] v1 = new float[128]; // all zeros
        float[] v2 = new float[128];
        Arrays.fill(v2, 0.5f);

        assertThrows(IllegalArgumentException.class, () -> FaceVerificationService.cosineSimilarity(v1, v2),
                "Zero vector norm must be rejected");
    }

    @Test
    void nanOrInfiniteValueThrows() {
        float[] v1 = new float[128];
        Arrays.fill(v1, 0.1f);
        v1[10] = Float.NaN;
        float[] v2 = new float[128];
        Arrays.fill(v2, 0.1f);

        assertThrows(IllegalArgumentException.class, () -> FaceVerificationService.cosineSimilarity(v1, v2));

        v1[10] = Float.POSITIVE_INFINITY;
        assertThrows(IllegalArgumentException.class, () -> FaceVerificationService.cosineSimilarity(v1, v2));
    }

    // ── 7. Input validation ──────────────────────────────────────────────────

    @Test
    void nullStudentThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.verifyStudentFace(null, new byte[]{1, 2, 3}));
    }

    @Test
    void nullStudentIdThrows() {
        Student s = Student.builder().id(null).build();
        assertThrows(IllegalArgumentException.class, () -> service.verifyStudentFace(s, new byte[]{1, 2, 3}));
    }

    @Test
    void emptyOrNullImageBytesThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.verifyStudentFace(student, null));
        assertThrows(IllegalArgumentException.class, () -> service.verifyStudentFace(student, new byte[0]));
    }

    // ── 8. Serialization utilities ───────────────────────────────────────────

    @Test
    void bytesToFloatArrayRoundTrip() {
        float[] original = new float[128];
        for (int i = 0; i < 128; i++) {
            original[i] = (float) (Math.sin(i) * 0.5);
        }
        byte[] bytes = floatsToBytes(original);
        float[] reconstructed = FaceVerificationService.bytesToFloatArray(bytes);

        assertArrayEquals(original, reconstructed, 1e-6f);
    }

    @Test
    void bytesToFloatArrayWrongLengthThrows() {
        assertThrows(IllegalArgumentException.class, () -> FaceVerificationService.bytesToFloatArray(new byte[511]));
        assertThrows(IllegalArgumentException.class, () -> FaceVerificationService.bytesToFloatArray(new byte[513]));
        assertThrows(IllegalArgumentException.class, () -> FaceVerificationService.bytesToFloatArray(null));
    }

    @Test
    void toFloatArrayWrongSizeThrows() {
        List<Number> tooShort = new ArrayList<>(Collections.nCopies(127, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> FaceVerificationService.toFloatArray(tooShort));

        List<Number> tooLong = new ArrayList<>(Collections.nCopies(129, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> FaceVerificationService.toFloatArray(tooLong));

        assertThrows(IllegalArgumentException.class, () -> FaceVerificationService.toFloatArray(null));
    }

    // ── 9. Passive Anti-Spoofing & Error Handling ─────────────────────────────

    @Test
    void spoofPresentationAttackDetectedReturnsLiveFalseAndDoesNotMatch() throws Exception {
        float[] registeredVector = new float[128];
        Arrays.fill(registeredVector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, registeredVector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        Map<String, Object> flaskSpoofResp = Map.of(
                "extracted", false,
                "is_real", false,
                "liveness_score", 0.045,
                "rejection_reason", "SPOOF_DETECTED",
                "error", "Presentation attack detected"
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(flaskSpoofResp));

        FaceVerificationResult result = service.verifyStudentFace(student, new byte[]{1, 2, 3});

        assertTrue(result.isFaceRegistered());
        assertFalse(result.isLive());
        assertFalse(result.isMatched());
        assertEquals(0.045, result.getLivenessScore());
        assertEquals("SPOOF_DETECTED", result.getRejectionReason());
        assertEquals(0.0, result.getSimilarity());
    }

    @Test
    void liveGenuineFaceWithLivenessScorePassed() throws Exception {
        float[] registeredVector = new float[128];
        Arrays.fill(registeredVector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, registeredVector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        float[] liveVector = Arrays.copyOf(registeredVector, 128);
        Map<String, Object> flaskLiveResp = Map.of(
                "extracted", true,
                "is_real", true,
                "liveness_score", 0.985,
                "embedding", floatsToList(liveVector)
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(flaskLiveResp));

        FaceVerificationResult result = service.verifyStudentFace(student, new byte[]{1, 2, 3});

        assertTrue(result.isFaceRegistered());
        assertTrue(result.isLive());
        assertTrue(result.isMatched());
        assertEquals(0.985, result.getLivenessScore());
        assertEquals(1.0, result.getSimilarity(), 0.001);
    }

    @Test
    void flask400ThrowsIllegalArgument() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThrows(IllegalArgumentException.class, () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));
    }

    @Test
    void flaskNoFaceRejectionPreservesOnlyAllowlistedReason() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        HttpClientErrorException flaskError = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                null,
                "{\"reason\":\"NO_FACE\",\"untrusted\":\"ignored\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(flaskError);

        FaceProcessingException error = assertThrows(FaceProcessingException.class,
                () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));

        assertEquals("NO_FACE", error.getReason());
        assertEquals("Face image could not be processed", error.getMessage());
    }

    @Test
    void flask500ThrowsHttpServerErrorException() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(HttpServerErrorException.class, () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));
    }

    @Test
    void flaskTimeoutThrowsResourceAccess() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThrows(ResourceAccessException.class, () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));
    }

    @Test
    void missingIsRealThrowsIllegalStateException() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        // is_real is completely missing from the map
        Map<String, Object> flaskResp = Map.of(
                "extracted", true,
                "liveness_score", 0.95,
                "embedding", floatsToList(vector)
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(flaskResp));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));
        assertTrue(ex.getMessage().contains("is_real must be explicitly boolean"));
    }

    @Test
    void nullIsRealThrowsIllegalStateException() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        // is_real is explicitly null
        Map<String, Object> flaskResp = new HashMap<>();
        flaskResp.put("is_real", null);
        flaskResp.put("extracted", true);
        flaskResp.put("liveness_score", 0.95);
        flaskResp.put("embedding", floatsToList(vector));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(flaskResp));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));
        assertTrue(ex.getMessage().contains("is_real must be explicitly boolean"));
    }

    @Test
    void nonBooleanIsRealThrowsIllegalStateException() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        // is_real is string "true" instead of boolean true
        Map<String, Object> flaskResp = Map.of(
                "is_real", "true",
                "extracted", true,
                "liveness_score", 0.95,
                "embedding", floatsToList(vector)
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(flaskResp));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));
        assertTrue(ex.getMessage().contains("is_real must be explicitly boolean"));
    }

    @Test
    void missingResponseBodyThrowsIllegalStateException() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        // Response body is null
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(null));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));
        assertTrue(ex.getMessage().contains("Empty response body from face service"));
    }

    @Test
    void missingLivenessScoreThrowsIllegalStateException() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        // liveness_score is omitted even though is_real = true
        Map<String, Object> flaskResp = Map.of(
                "is_real", true,
                "extracted", true,
                "embedding", floatsToList(vector)
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(flaskResp));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));
        assertTrue(ex.getMessage().contains("missing or invalid liveness_score"));
    }

    @Test
    void nullLivenessScoreWithoutSkippedStatusStillThrows() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        // liveness_score is explicitly null and passive_antispoof_status is
        // absent -- production behavior: must reject (not auto-accept).
        Map<String, Object> flaskResp2 = new HashMap<>();
        flaskResp2.put("is_real", true);
        flaskResp2.put("extracted", true);
        flaskResp2.put("liveness_score", null);
        flaskResp2.put("embedding", floatsToList(vector));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(flaskResp2));

        IllegalStateException ex2 = assertThrows(IllegalStateException.class,
                () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));
        assertTrue(ex2.getMessage().contains("missing or invalid liveness_score"));
    }

    @Test
    void malformedNonNumericLivenessScoreThrowsIllegalStateException() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        // liveness_score is a non-numeric string
        Map<String, Object> flaskResp = Map.of(
                "is_real", true,
                "liveness_score", "not-a-number",
                "extracted", true,
                "embedding", floatsToList(vector)
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(flaskResp));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));
        assertTrue(ex.getMessage().contains("missing or invalid liveness_score"));
    }

    @Test
    void nonFiniteOrOutOfRangeLivenessScoreThrowsIllegalStateException() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        // NaN score
        Map<String, Object> nanResp = Map.of(
                "is_real", true,
                "liveness_score", Double.NaN,
                "extracted", true,
                "embedding", floatsToList(vector)
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(nanResp));

        assertThrows(IllegalStateException.class, () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));

        // Out of range (> 1.0)
        Map<String, Object> outOfRangeResp = Map.of(
                "is_real", true,
                "liveness_score", 1.5,
                "extracted", true,
                "embedding", floatsToList(vector)
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(outOfRangeResp));

        assertThrows(IllegalStateException.class, () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));

        // Out of range (< 0.0)
        Map<String, Object> negativeResp = Map.of(
                "is_real", true,
                "liveness_score", -0.1,
                "extracted", true,
                "embedding", floatsToList(vector)
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(negativeResp));

        assertThrows(IllegalStateException.class, () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));
    }

    @Test
    void invalidFlaskJsonOrMalformedEmbeddingThrows() throws Exception {
        float[] vector = new float[128];
        Arrays.fill(vector, 0.1f);
        FaceEmbedding fe = createEncryptedEmbedding(student, vector);
        when(embeddingRepo.findByStudent_Id(student.getId())).thenReturn(Optional.of(fe));

        // Case 1: extracted is false
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "is_real", true,
                        "liveness_score", 0.95,
                        "extracted", false)));
        assertThrows(IllegalStateException.class, () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));

        // Case 2: embedding is not a list
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "is_real", true,
                        "liveness_score", 0.95,
                        "extracted", true,
                        "embedding", "invalid_string")));
        assertThrows(IllegalStateException.class, () -> service.verifyStudentFace(student, new byte[]{1, 2, 3}));
    }
}
