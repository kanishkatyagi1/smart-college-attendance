package com.college.attendance.service;

import com.college.attendance.service.ActiveLivenessEvaluator.Decision;
import com.college.attendance.service.ActiveLivenessEvaluator.Evaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpActiveLivenessEvaluator}.
 *
 * Uses a mocked {@link RestTemplate} — no running Flask service is required.
 */
class HttpActiveLivenessEvaluatorTest {

    private static final String FLASK_URL   = "http://localhost:5001";
    private static final String FLASK_SECRET = "test-secret-eval";
    private static final String PROCESS_URL  = FLASK_URL + "/active-liveness/process";
    private static final String SESSION_ID   = "test-session-id";
    private static final byte[] FRAME_BYTES  = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
    private static final List<ActiveLivenessAction> BLINK = List.of(ActiveLivenessAction.BLINK);

    @Mock
    private RestTemplate restTemplate;

    private HttpActiveLivenessEvaluator evaluator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        evaluator = new HttpActiveLivenessEvaluator(restTemplate);
        ReflectionTestUtils.setField(evaluator, "faceServiceUrl",   FLASK_URL);
        ReflectionTestUtils.setField(evaluator, "faceServiceSecret", FLASK_SECRET);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void mockFlask(String responseBody) {
        when(restTemplate.exchange(eq(PROCESS_URL), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));
    }

    private void mockFlask4xx(HttpStatus status) {
        when(restTemplate.exchange(eq(PROCESS_URL), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(status, status.getReasonPhrase(),
                        HttpHeaders.EMPTY, new byte[0], null));
    }

    private void mockFlask5xx(HttpStatus status) {
        when(restTemplate.exchange(eq(PROCESS_URL), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpServerErrorException.create(status, status.getReasonPhrase(),
                        HttpHeaders.EMPTY, new byte[0], null));
    }

    // -----------------------------------------------------------------------
    // 1. Correct Flask endpoint URL
    // -----------------------------------------------------------------------

    @Test
    void callsCorrectFlaskEndpoint() {
        mockFlask("{\"decision\":\"PENDING\",\"reason\":null}");
        evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");

        verify(restTemplate).exchange(eq(PROCESS_URL), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class));
    }

    // -----------------------------------------------------------------------
    // 2. X-FACE-SERVICE-SECRET header is present and correct
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void sendsCorrectSecretHeader() {
        mockFlask("{\"decision\":\"PENDING\",\"reason\":null}");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");

        verify(restTemplate).exchange(eq(PROCESS_URL), eq(HttpMethod.POST),
                captor.capture(), eq(String.class));
        HttpEntity<?> entity = captor.getValue();
        assertEquals(FLASK_SECRET,
                entity.getHeaders().getFirst("X-FACE-SERVICE-SECRET"),
                "X-FACE-SERVICE-SECRET header must match configured secret");
    }

    // -----------------------------------------------------------------------
    // 3. Multipart frame is forwarded (Content-Type multipart/form-data)
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void sendsMultipartFormData() {
        mockFlask("{\"decision\":\"PENDING\",\"reason\":null}");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");

        verify(restTemplate).exchange(eq(PROCESS_URL), eq(HttpMethod.POST),
                captor.capture(), eq(String.class));
        HttpEntity<?> entity = captor.getValue();
        MediaType ct = entity.getHeaders().getContentType();
        assertNotNull(ct);
        assertTrue(ct.isCompatibleWith(MediaType.MULTIPART_FORM_DATA),
                "Content-Type must be multipart/form-data");
    }

    // -----------------------------------------------------------------------
    // 4. All supported decision mappings
    // -----------------------------------------------------------------------

    @Test
    void decisionPendingMapped() {
        mockFlask("{\"decision\":\"PENDING\",\"reason\":null}");
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");
        assertEquals(Decision.PENDING, eval.decision());
        assertNull(eval.safeReason());
    }

    @Test
    void decisionPassedMapped() {
        mockFlask("{\"decision\":\"PASSED\",\"reason\":null}");
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 1, FRAME_BYTES, "image/jpeg");
        assertEquals(Decision.PASSED, eval.decision());
    }

    @Test
    void decisionFailedMapped() {
        mockFlask("{\"decision\":\"FAILED\",\"reason\":\"SESSION_EXPIRED\"}");
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 2, FRAME_BYTES, "image/jpeg");
        assertEquals(Decision.FAILED, eval.decision());
        assertEquals("SESSION_EXPIRED", eval.safeReason());
    }

    @Test
    void decisionRetryableFailureMapped() {
        mockFlask("{\"decision\":\"RETRYABLE_FAILURE\",\"reason\":\"NO_FACE\"}");
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 3, FRAME_BYTES, "image/jpeg");
        assertEquals(Decision.RETRYABLE_FAILURE, eval.decision());
        assertEquals("NO_FACE", eval.safeReason());
    }

    // -----------------------------------------------------------------------
    // 5. Challenge sequence is serialised as JSON array
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void challengeSeqSerialisedAsJsonArray() {
        mockFlask("{\"decision\":\"PENDING\",\"reason\":null}");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        List<ActiveLivenessAction> multiChallenge =
                List.of(ActiveLivenessAction.BLINK, ActiveLivenessAction.TURN_LEFT);
        evaluator.evaluate(SESSION_ID, multiChallenge, 0, FRAME_BYTES, "image/jpeg");

        verify(restTemplate).exchange(eq(PROCESS_URL), eq(HttpMethod.POST),
                captor.capture(), eq(String.class));
        @SuppressWarnings("rawtypes")
        MultiValueMap body = (MultiValueMap) captor.getValue().getBody();
        assertNotNull(body);
        String challengeJson = String.valueOf(body.getFirst("challenge_seq"));
        assertEquals("[\"BLINK\",\"TURN_LEFT\"]", challengeJson,
                "challenge_seq must be a JSON array of action name strings");
    }

    // -----------------------------------------------------------------------
    // 6. Invalid (unknown) decision string from Flask
    // -----------------------------------------------------------------------

    @Test
    void invalidDecisionStringReturnsFailedClosed() {
        mockFlask("{\"decision\":\"UNKNOWN_DECISION\",\"reason\":null}");
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");
        assertEquals(Decision.FAILED, eval.decision(),
                "Unknown decision string must map to FAILED (fail-closed)");
        assertEquals("MALFORMED_RESPONSE", eval.safeReason());
    }

    // -----------------------------------------------------------------------
    // 7. Malformed JSON response
    // -----------------------------------------------------------------------

    @Test
    void malformedJsonReturnsRetryableFailure() {
        mockFlask("not valid json {{{{");
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");
        // Any Exception path that is not ResourceAccess returns RETRYABLE_FAILURE.
        assertEquals(Decision.RETRYABLE_FAILURE, eval.decision());
        assertEquals("EVALUATOR_UNAVAILABLE", eval.safeReason());
    }

    // -----------------------------------------------------------------------
    // 8. HTTP 4xx response from Flask
    // -----------------------------------------------------------------------

    @Test
    void http401FromFlaskReturnsRetryableFailure() {
        mockFlask4xx(HttpStatus.UNAUTHORIZED);
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");
        assertEquals(Decision.RETRYABLE_FAILURE, eval.decision());
    }

    @Test
    void http400FromFlaskReturnsRetryableFailure() {
        mockFlask4xx(HttpStatus.BAD_REQUEST);
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");
        assertEquals(Decision.RETRYABLE_FAILURE, eval.decision());
    }

    // -----------------------------------------------------------------------
    // 9. HTTP 5xx response from Flask
    // -----------------------------------------------------------------------

    @Test
    void http500FromFlaskReturnsRetryableFailure() {
        mockFlask5xx(HttpStatus.INTERNAL_SERVER_ERROR);
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");
        assertEquals(Decision.RETRYABLE_FAILURE, eval.decision());
    }

    // -----------------------------------------------------------------------
    // 10. Connection timeout / unreachable Flask
    // -----------------------------------------------------------------------

    @Test
    void connectionTimeoutReturnsRetryableFailure() {
        when(restTemplate.exchange(eq(PROCESS_URL), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");
        assertEquals(Decision.RETRYABLE_FAILURE, eval.decision());
        assertEquals("EVALUATOR_UNAVAILABLE", eval.safeReason());
    }

    // -----------------------------------------------------------------------
    // 11. Null / empty frame bytes (fail-closed before HTTP call)
    // -----------------------------------------------------------------------

    @Test
    void nullFrameBytesFailsClosed() {
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, null, "image/jpeg");
        assertEquals(Decision.RETRYABLE_FAILURE, eval.decision());
        assertEquals("EMPTY_FRAME", eval.safeReason());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void emptyFrameBytesFailsClosed() {
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, new byte[0], "image/jpeg");
        assertEquals(Decision.RETRYABLE_FAILURE, eval.decision());
        assertEquals("EMPTY_FRAME", eval.safeReason());
        verifyNoInteractions(restTemplate);
    }

    // -----------------------------------------------------------------------
    // 12. SAFE_REASON validation — unsafe reason strings are dropped
    // -----------------------------------------------------------------------

    @Test
    void unsafeReasonStringIsDropped() {
        // Lowercase letters, spaces, injection attempts must all be sanitised to null.
        mockFlask("{\"decision\":\"FAILED\",\"reason\":\"<script>alert(1)</script>\"}");
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");
        assertEquals(Decision.FAILED, eval.decision());
        assertNull(eval.safeReason(), "Unsafe reason string must be dropped (null)");
    }

    @Test
    void safeReasonWithValidPattern() {
        mockFlask("{\"decision\":\"FAILED\",\"reason\":\"SPOOF_DETECTED\"}");
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");
        assertEquals("SPOOF_DETECTED", eval.safeReason());
    }

    @Test
    void reasonTooLong_isDropped() {
        // Pattern requires {1,64} — 65 uppercase characters must be dropped.
        String longReason = "A".repeat(65);
        mockFlask("{\"decision\":\"FAILED\",\"reason\":\"" + longReason + "\"}");
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");
        assertEquals(Decision.FAILED, eval.decision());
        assertNull(eval.safeReason(), "Reason longer than 64 chars must be dropped");
    }

    // -----------------------------------------------------------------------
    // 13. Null reason in response is handled
    // -----------------------------------------------------------------------

    @Test
    void nullReasonFieldIsAllowed() {
        mockFlask("{\"decision\":\"PENDING\",\"reason\":null}");
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");
        assertEquals(Decision.PENDING, eval.decision());
        assertNull(eval.safeReason());
    }

    // -----------------------------------------------------------------------
    // 14. Active challenge success alone does not create an attendance record
    //     (structural: PASSED decision is returned as-is; Spring's session
    //     service, not Flask, decides whether to mark attendance)
    // -----------------------------------------------------------------------

    @Test
    void passedDecisionReturnedWithoutSideEffect() {
        mockFlask("{\"decision\":\"PASSED\",\"reason\":null}");
        Evaluation eval = evaluator.evaluate(SESSION_ID, BLINK, 0, FRAME_BYTES, "image/jpeg");
        // The evaluator just returns the decision. It has no repository or attendance
        // service dependency — asserting no other interactions on restTemplate is
        // sufficient to confirm it did not make a second call to record attendance.
        assertEquals(Decision.PASSED, eval.decision());
        verify(restTemplate, times(1)).exchange(anyString(), any(), any(), eq(String.class));
    }

    // -----------------------------------------------------------------------
    // 15. Frame sequence forwarded correctly
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void frameSequenceIsForwarded() {
        mockFlask("{\"decision\":\"PENDING\",\"reason\":null}");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        evaluator.evaluate(SESSION_ID, BLINK, 42L, FRAME_BYTES, "image/jpeg");

        verify(restTemplate).exchange(eq(PROCESS_URL), eq(HttpMethod.POST),
                captor.capture(), eq(String.class));
        @SuppressWarnings("rawtypes")
        MultiValueMap body = (MultiValueMap) captor.getValue().getBody();
        assertEquals("42", String.valueOf(body.getFirst("frame_seq")),
                "frame_seq must match the sequence argument");
        assertEquals(SESSION_ID, String.valueOf(body.getFirst("session_id")));
    }
}
