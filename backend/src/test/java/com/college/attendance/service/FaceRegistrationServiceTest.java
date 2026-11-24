package com.college.attendance.service;

import com.college.attendance.entity.FaceEmbedding;
import com.college.attendance.entity.Student;
import com.college.attendance.entity.User;
import com.college.attendance.repo.FaceEmbeddingRepository;
import com.college.attendance.repo.StudentRepository;
import com.college.attendance.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FaceRegistrationServiceTest {

    @Mock private StudentRepository studentRepo;
    @Mock private FaceEmbeddingRepository embeddingRepo;
    @Mock private UserRepository userRepo;
    @Mock private RestTemplate restTemplate;

    private FaceRegistrationService service;
    private User activeApprovedUser;
    private Student student;

    /** 128-element embedding – matches the EXPECTED_EMBEDDING_DIM constant. */
    private static final List<Number> VALID_EMBEDDING =
            Collections.nCopies(128, (Number) 0.1f);

    /** Flask success response body. */
    private static Map<String, Object> successBody() {
        return Map.of("extracted", true, "embedding", VALID_EMBEDDING);
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Fix encryption key so EncryptionUtil.encrypt() works without env var.
        System.setProperty("face.emb.key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        System.setProperty("face.emb.key.useEnv", "false");

        activeApprovedUser = User.builder()
                .id(1L)
                .email("student@gbu.ac.in")
                .active(true)
                .approvalStatus(com.college.attendance.entity.ApprovalStatus.APPROVED)
                .build();

        student = Student.builder()
                .id(10L)
                .user(activeApprovedUser)
                .build();

        when(userRepo.findByEmail(anyString())).thenReturn(Optional.of(activeApprovedUser));
        when(studentRepo.findByUser(activeApprovedUser)).thenReturn(Optional.of(student));

        service = new FaceRegistrationService(studentRepo, embeddingRepo, userRepo, restTemplate);

        // Inject config values directly (bypassing Spring context)
        setField(service, "faceServiceUrl", "http://localhost:5001");
        setField(service, "faceServiceSecret", "test-secret");

        // Run the @PostConstruct guard so existing tests exercise a fully-validated instance.
        service.validateConfig();
    }

    @AfterEach
    void restoreKey() {
        System.setProperty("face.emb.key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        System.setProperty("face.emb.key.useEnv", "false");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Inject a private/package-private field via reflection (avoids Spring context). */
    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("setField failed for " + fieldName, e);
        }
    }

    private void stubFlask(Map<String, Object> body) {
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    private void stubFlaskThrows(RuntimeException ex) {
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(ex);
    }

    // ── 1. Happy path ─────────────────────────────────────────────────────────

    @Test
    void successfulRegistrationEncryptsAndPersists() {
        when(embeddingRepo.existsByStudent_Id(student.getId())).thenReturn(false);
        stubFlask(successBody());

        service.registerFace(activeApprovedUser.getEmail(), new byte[]{1, 2, 3});

        ArgumentCaptor<FaceEmbedding> captor = ArgumentCaptor.forClass(FaceEmbedding.class);
        verify(embeddingRepo, times(1)).save(captor.capture());
        FaceEmbedding saved = captor.getValue();

        assertEquals(student, saved.getStudent());
        assertNotNull(saved.getNonce(),       "nonce must be persisted");
        assertEquals(12, saved.getNonce().length, "GCM nonce must be 12 bytes");
        assertNotNull(saved.getCiphertext(),  "ciphertext must be persisted");
        assertNotNull(saved.getTag(),         "auth tag must be persisted");
        assertEquals(16, saved.getTag().length,   "GCM tag must be 16 bytes");
        assertEquals("v1", saved.getEncryptionVersion());
        assertNotNull(saved.getCreatedAt(),   "createdAt must be non-null");
        assertNotNull(saved.getUpdatedAt(),   "updatedAt must be non-null");
    }

    @Test
    void embeddingIsEncryptedBeforePersisting() {
        // Verifies ciphertext != plaintext raw bytes (i.e., encryption did run).
        when(embeddingRepo.existsByStudent_Id(student.getId())).thenReturn(false);
        stubFlask(successBody());

        service.registerFace(activeApprovedUser.getEmail(), new byte[]{1, 2, 3});

        ArgumentCaptor<FaceEmbedding> captor = ArgumentCaptor.forClass(FaceEmbedding.class);
        verify(embeddingRepo).save(captor.capture());
        FaceEmbedding saved = captor.getValue();

        // Plain embedding = 128 floats × 4 bytes = 512 bytes.
        // Ciphertext should be exactly that length (GCM adds no padding).
        assertEquals(512, saved.getCiphertext().length,
                "Ciphertext length must equal 128 × 4 = 512 bytes");
    }

    // ── 2. Active/approved guards ─────────────────────────────────────────────

    @Test
    void inactiveStudentIsRejected() {
        activeApprovedUser.setActive(false);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.registerFace(activeApprovedUser.getEmail(), new byte[]{1}));
        assertTrue(ex.getMessage().contains("not active"),
                "Exception must mention 'not active'");
        verify(embeddingRepo, never()).save(any());
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(Map.class));
    }

    @Test
    void unapprovedStudentIsRejected() {
        activeApprovedUser.setApprovalStatus(
                com.college.attendance.entity.ApprovalStatus.PENDING);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.registerFace(activeApprovedUser.getEmail(), new byte[]{1}));
        assertTrue(ex.getMessage().contains("not active or not approved"));
        verify(embeddingRepo, never()).save(any());
    }

    // ── 3. Duplicate guard ────────────────────────────────────────────────────

    @Test
    void duplicateRegistrationIsRejected() {
        when(embeddingRepo.existsByStudent_Id(student.getId())).thenReturn(true);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.registerFace(activeApprovedUser.getEmail(), new byte[]{1}));
        assertTrue(ex.getMessage().contains("already registered"));
        verify(embeddingRepo, never()).save(any());
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(Map.class));
    }

    // ── 4. Flask unreachable ──────────────────────────────────────────────────

    @Test
    void flaskServiceUnavailableThrowsIllegalState() {
        when(embeddingRepo.existsByStudent_Id(student.getId())).thenReturn(false);
        stubFlaskThrows(new ResourceAccessException("connection refused"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.registerFace(activeApprovedUser.getEmail(), new byte[]{1}));
        assertTrue(ex.getMessage().contains("Face service unavailable"));
        verify(embeddingRepo, never()).save(any());
    }

    // ── 5. Flask HTTP 400 (bad image / no face / multiple faces) ─────────────
    // S-4: exception message must be generic — must NOT expose the upstream status code.

    @Test
    void flask400ThrowsIllegalArgumentWithGenericMessage() {
        when(embeddingRepo.existsByStudent_Id(student.getId())).thenReturn(false);
        stubFlaskThrows(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", null, null, null));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerFace(activeApprovedUser.getEmail(), new byte[]{1}));
        // S-4: message is a fixed generic string — no "HTTP 400" leakage
        assertFalse(ex.getMessage().contains("HTTP 400"),
                "S-4: Flask HTTP status code must not be included in the client-facing message");
        assertFalse(ex.getMessage().contains("HTTP 401"),
                "S-4: Flask HTTP status code must not be included in the client-facing message");
        // Message must still be meaningful (not blank)
        assertFalse(ex.getMessage().isBlank(), "Exception message must not be blank");
        verify(embeddingRepo, never()).save(any());
    }

    // ── 6. Flask HTTP 401 (wrong secret) ─────────────────────────────────────
    // S-4: 401 from Flask must NOT be leaked to the client. The service logs it
    // at WARN level but throws the same generic message regardless of status code.

    @Test
    void flask401ThrowsIllegalArgumentWithGenericMessage() {
        when(embeddingRepo.existsByStudent_Id(student.getId())).thenReturn(false);
        stubFlaskThrows(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerFace(activeApprovedUser.getEmail(), new byte[]{1}));
        // S-4: a 401 must not be distinguishable from a 400 by the client
        assertFalse(ex.getMessage().contains("401"),
                "S-4: HTTP 401 must not appear in the client-facing exception message");
        assertFalse(ex.getMessage().contains("HTTP"),
                "S-4: No raw HTTP status should appear in the exception message");
        assertFalse(ex.getMessage().isBlank(), "Exception message must not be blank");
        verify(embeddingRepo, never()).save(any());
    }

    // ── 7. Flask returns extracted=false ─────────────────────────────────────

    @Test
    void flaskExtractionFailureRejected() {
        when(embeddingRepo.existsByStudent_Id(student.getId())).thenReturn(false);
        stubFlask(Map.of("extracted", false, "error", "No face detected"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.registerFace(activeApprovedUser.getEmail(), new byte[]{1}));
        assertTrue(ex.getMessage().contains("extraction unsuccessful")
                || ex.getMessage().contains("extracted"));
        verify(embeddingRepo, never()).save(any());
    }

    // ── 8. Flask returns wrong embedding dimension ────────────────────────────

    @Test
    void wrongEmbeddingDimensionRejected() {
        when(embeddingRepo.existsByStudent_Id(student.getId())).thenReturn(false);
        // 5 floats instead of 128
        Map<String, Object> body = Map.of(
                "extracted", true,
                "embedding", Collections.nCopies(5, (Number) 0.1f));
        stubFlask(body);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.registerFace(activeApprovedUser.getEmail(), new byte[]{1}));
        assertTrue(ex.getMessage().contains("128") || ex.getMessage().contains("dimension"),
                "Exception must mention expected dimension");
        verify(embeddingRepo, never()).save(any());
    }

    // ── 9. Flask returns missing embedding field ──────────────────────────────

    @Test
    void missingEmbeddingFieldRejected() {
        when(embeddingRepo.existsByStudent_Id(student.getId())).thenReturn(false);
        // extracted=true but no "embedding" key
        stubFlask(Map.of("extracted", true));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.registerFace(activeApprovedUser.getEmail(), new byte[]{1}));
        assertTrue(ex.getMessage().toLowerCase().contains("embedding"));
        verify(embeddingRepo, never()).save(any());
    }

    // ── 10. Request must be multipart (not raw bytes) ─────────────────────────

    @Test
    void requestSentAsMultipartFormData() {
        when(embeddingRepo.existsByStudent_Id(student.getId())).thenReturn(false);
        stubFlask(successBody());

        service.registerFace(activeApprovedUser.getEmail(), new byte[]{1, 2, 3});

        // Capture the HttpEntity and verify it carries a MultiValueMap (multipart body)
        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://localhost:5001/extract-embedding"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(Map.class));

        HttpEntity<?> sent = entityCaptor.getValue();
        // Content-Type header must be multipart/form-data
        assertTrue(sent.getHeaders().getContentType().includes(
                org.springframework.http.MediaType.MULTIPART_FORM_DATA),
                "Request Content-Type must be multipart/form-data");
        // Body must be a MultiValueMap (not a byte[])
        assertInstanceOf(org.springframework.util.MultiValueMap.class, sent.getBody(),
                "Request body must be a MultiValueMap for multipart");
    }

    // ── 11. S-2: startup validation rejects null secret ───────────────────────

    @Test
    void nullSecretFailsStartup() {
        FaceRegistrationService svc =
                new FaceRegistrationService(studentRepo, embeddingRepo, userRepo, restTemplate);
        setField(svc, "faceServiceUrl", "http://localhost:5001");
        // faceServiceSecret left as null (Spring @Value not invoked in unit test)

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                svc::validateConfig,
                "validateConfig() must throw when faceServiceSecret is null");

        // Message must mention how to fix the problem …
        assertTrue(ex.getMessage().contains("FACE_SERVICE_SECRET"),
                "Exception message must name the environment variable");
        // … but must never contain the actual secret value (null-safety check)
        assertFalse(ex.getMessage().contains("null"),
                "Exception message must not echo 'null' — avoids leaking type info");
    }

    // ── 12. S-2: startup validation rejects blank secret ─────────────────────

    @Test
    void blankSecretFailsStartup() {
        FaceRegistrationService svc =
                new FaceRegistrationService(studentRepo, embeddingRepo, userRepo, restTemplate);
        setField(svc, "faceServiceUrl", "http://localhost:5001");
        setField(svc, "faceServiceSecret", "   "); // whitespace only

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                svc::validateConfig,
                "validateConfig() must throw when faceServiceSecret is blank");

        // Message describes the fix, never echoes the secret value
        assertTrue(ex.getMessage().contains("FACE_SERVICE_SECRET"),
                "Exception message must name the environment variable");
        assertFalse(ex.getMessage().contains("   "),
                "Exception message must not echo the blank secret value");
    }

    // ── 13. G-1: Flask HTTP 5xx → RuntimeException, no status/body leaked ─────
    // S-4: a 5xx from Flask must not expose the upstream HTTP status code,
    // the Flask response body, or any internal exception class name to the caller.

    @Test
    void flask5xxThrowsRuntimeExceptionWithGenericMessage() {
        when(embeddingRepo.existsByStudent_Id(student.getId())).thenReturn(false);
        // RestTemplate throws HttpServerErrorException for 5xx responses
        stubFlaskThrows(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", null, null, null));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.registerFace(activeApprovedUser.getEmail(), new byte[]{1}));

        // S-4: The exception message must be a fixed generic string.
        // The Flask HTTP status code, response body, and class name must not appear.
        assertFalse(ex.getMessage().contains("500"),
                "G-1/S-4: HTTP 500 must not appear in the exception message");
        assertFalse(ex.getMessage().contains("Internal Server Error"),
                "G-1/S-4: Flask response phrase must not appear in the exception message");
        assertFalse(ex.getMessage().contains("HttpServerError"),
                "G-1/S-4: Spring exception class name must not appear in the exception message");
        assertFalse(ex.getMessage().isBlank(),
                "Exception message must not be blank");
        verify(embeddingRepo, never()).save(any());
    }

    // ── 14. G-3: Unexpected RuntimeException → propagates; no DB save ─────────
    // Verifies that an unexpected non-HTTP RuntimeException from the RestTemplate
    // (e.g. a codec failure, NPE in a converter) is caught by the general
    // catch (Exception) block, wrapped with a fixed message, and never persists.

    @Test
    void unexpectedRuntimeExceptionFromFlaskDoesNotPersist() {
        when(embeddingRepo.existsByStudent_Id(student.getId())).thenReturn(false);
        // Simulate an unexpected non-HTTP failure (e.g. codec/converter error)
        stubFlaskThrows(new RuntimeException("Simulated unexpected codec failure"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.registerFace(activeApprovedUser.getEmail(), new byte[]{1}));

        // The re-thrown exception must use a generic wrapper message.
        assertEquals("Unexpected error calling face service", ex.getMessage(),
                "G-3: unexpected failures must be wrapped with the fixed generic message");
        // The original cause is attached for server-side diagnostics, not the response.
        assertNotNull(ex.getCause(), "Cause must be preserved for server-side logging");
        verify(embeddingRepo, never()).save(any());
    }
}
