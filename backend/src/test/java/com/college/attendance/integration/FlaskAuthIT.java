package com.college.attendance.integration;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Live integration test: verifies the Spring Boot → Flask HTTP authentication
 * contract using deliberately corrupt image bytes.
 *
 * <h3>What this test proves</h3>
 * Flask's {@code _check_secret()} runs <em>before</em> {@code cv2.imdecode()}.
 * Therefore:
 * <ul>
 *   <li>Wrong/absent secret → HTTP 401 (auth rejected)</li>
 *   <li>Correct secret + corrupt bytes → HTTP 400 (auth accepted; decode failed)</li>
 * </ul>
 * HTTP 400 on corrupt bytes is <strong>definitive proof</strong> that the
 * configured {@code FACE_SERVICE_SECRET} matches the Flask server's secret.
 *
 * <h3>Safety guarantees</h3>
 * <ul>
 *   <li>No Spring application context — no DB connection, no JPA, no student lookup.</li>
 *   <li>No face embedding produced or stored (corrupt bytes fail before YuNet).</li>
 *   <li>No {@code face_embeddings} row created.</li>
 *   <li>No real face image used.</li>
 *   <li>Secret read from environment; never printed or logged.</li>
 *   <li>Flask's {@code /extract-embedding} contains no file-write code on the 400 path.</li>
 * </ul>
 *
 * <h3>Execution</h3>
 * This file ends in {@code IT.java}, so Maven Surefire's default inclusions
 * ({@code **\/*Test.java} etc.) skip it during {@code mvn test}.
 * Run explicitly:
 * <pre>
 *   $env:FACE_SERVICE_SECRET='your-secret'
 *   mvn test -pl backend -Dtest=FlaskAuthIT
 * </pre>
 *
 * When Flask is not reachable, every test is marked ABORTED (not FAILED).
 */
@Tag("integration")
@DisplayName("Flask authentication — live service integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlaskAuthIT {

    /**
     * Flask base URL — matches face.service.url in application.properties.
     * Override via FACE_SERVICE_URL env var if needed.
     */
    private static final String FLASK_URL =
            System.getenv().getOrDefault("FACE_SERVICE_URL", "http://localhost:5001");

    private static final String EXTRACT_ENDPOINT = FLASK_URL + "/extract-embedding";

    /**
     * Bytes that {@code cv2.imdecode} cannot parse.
     * Auth runs before decode, so with the right secret we get 400 not 401.
     * These bytes are deliberately meaningless — not a real image, not a face.
     */
    private static final byte[] CORRUPT_IMAGE_BYTES = {1, 2, 3, 4, 5};

    private RestTemplate restTemplate;

    // ── Shared setup ─────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        // Plain RestTemplate — no Spring context required.
        // Mimics what FaceRegistrationService uses, minus the managed timeouts.
        restTemplate = new RestTemplate();
    }

    @BeforeEach
    void assumeFlaskReachable() {
        // Skip (ABORTED, not FAILED) when Flask is not running.
        try {
            restTemplate.getForEntity(FLASK_URL + "/health", String.class);
        } catch (ResourceAccessException e) {
            assumeTrue(false,
                    "Flask not reachable at " + FLASK_URL + " — skipping integration tests. " +
                    "Start Flask (python app.py) and re-run.");
        }
    }

    // ── Helper: build the identical multipart request FaceRegistrationService sends ──

    /**
     * Builds a {@code multipart/form-data} request exactly matching
     * {@code FaceRegistrationService.callFlaskExtract()}:
     * field name {@code "image"}, filename {@code "face.jpg"},
     * header {@code X-FACE-SERVICE-SECRET}.
     */
    private HttpEntity<MultiValueMap<String, Object>> buildRequest(String secret) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(CORRUPT_IMAGE_BYTES) {
            @Override
            public String getFilename() { return "face.jpg"; }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (secret != null) {
            // Exactly the header name FaceRegistrationService sets (line 152 of service)
            headers.set("X-FACE-SERVICE-SECRET", secret);
        }
        return new HttpEntity<>(body, headers);
    }

    // ── T1: No header → 401 ──────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("T1 — No X-FACE-SERVICE-SECRET header → HTTP 401 or connection abort (auth enforced)")
    void noSecretHeaderReturns401() {
        // Flask's Werkzeug dev server sometimes RST-closes the connection when
        // sending a 401 on a multipart request (the client hasn't finished
        // uploading the body yet).  Both outcomes prove authentication is enforced:
        //   - HttpClientErrorException(401)  — full response received
        //   - ResourceAccessException        — Werkzeug aborted before body upload
        // A successful 200 or 400 response would indicate a bug (auth not enforced).

        Exception thrown = assertThrows(
                Exception.class,                       // accept either subtype
                () -> restTemplate.exchange(
                        EXTRACT_ENDPOINT, HttpMethod.POST,
                        buildRequest(null), String.class),
                "Flask must reject requests that have no X-FACE-SERVICE-SECRET header");

        boolean isAuthRejection =
                (thrown instanceof HttpClientErrorException hce
                        && hce.getStatusCode() == HttpStatus.UNAUTHORIZED)
                || (thrown instanceof ResourceAccessException);

        assertTrue(isAuthRejection,
                "Expected either HttpClientErrorException(401) or ResourceAccessException " +
                "(Werkzeug abort), but got: " + thrown.getClass().getSimpleName() +
                " — " + thrown.getMessage());

        // If we got a full 401 response, also check the body
        if (thrown instanceof HttpClientErrorException hce) {
            String body = hce.getResponseBodyAsString();
            if (!body.isBlank()) {
                assertFalse(body.contains("\"extracted\":true")
                                || body.contains("\"extracted\": true"),
                        "extracted must be false on auth rejection");
            }
        }

        System.out.println("[T1 PASS] No secret → auth rejected (" +
                thrown.getClass().getSimpleName() + "). Auth is enforced on Flask.");
    }

    // ── T2: Wrong secret → 401 ───────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("T2 — Wrong X-FACE-SERVICE-SECRET → HTTP 401 or connection abort (bad secret rejected)")
    void wrongSecretReturns401() {
        // Same Werkzeug behaviour as T1: may RST instead of sending full 401 body.
        Exception thrown = assertThrows(
                Exception.class,
                () -> restTemplate.exchange(
                        EXTRACT_ENDPOINT, HttpMethod.POST,
                        buildRequest("definitely-wrong-secret-xyzzy-99"), String.class),
                "Flask must reject requests with an incorrect secret value");

        boolean isAuthRejection =
                (thrown instanceof HttpClientErrorException hce
                        && hce.getStatusCode() == HttpStatus.UNAUTHORIZED)
                || (thrown instanceof ResourceAccessException);

        assertTrue(isAuthRejection,
                "Expected HttpClientErrorException(401) or ResourceAccessException, but got: " +
                thrown.getClass().getSimpleName() + " — " + thrown.getMessage());

        if (thrown instanceof HttpClientErrorException hce) {
            String body = hce.getResponseBodyAsString();
            if (!body.isBlank()) {
                assertFalse(body.contains("\"extracted\":true")
                                || body.contains("\"extracted\": true"),
                        "extracted must be false on auth rejection");
            }
        }

        System.out.println("[T2 PASS] Wrong secret → auth rejected (" +
                thrown.getClass().getSimpleName() + "). Bad secrets are rejected.");
    }

    // ── T3: Correct secret + corrupt image → 400 (not 401) ───────────────────
    //
    // This is the definitive authentication proof.
    // 400 = Flask accepted the secret and reached the image-decode step.
    // 401 = secret mismatch (the FACE_SERVICE_SECRET values differ between services).
    //
    // Requires FACE_SERVICE_SECRET env var — test is skipped if absent.

    @Test
    @Order(3)
    @DisplayName("T3 — Correct secret + corrupt bytes → HTTP 400, NOT 401 (★ auth accepted)")
    void correctSecretCorruptImageReturns400NotUnauthorized() {
        // Read secret from environment; also try system property for IDE/CI runs
        String secret = System.getenv("FACE_SERVICE_SECRET");
        if (secret == null || secret.isBlank()) {
            secret = System.getProperty("FACE_SERVICE_SECRET");
        }
        // Skip (ABORTED, not FAILED) when secret is not available
        assumeTrue(secret != null && !secret.isBlank(),
                "FACE_SERVICE_SECRET not set — skipping T3. " +
                "Set the env var and re-run: mvn test -Dtest=FlaskAuthIT");

        // Safety: print only the length — the value is never logged
        System.out.println("[T3] FACE_SERVICE_SECRET present, length=" + secret.length());

        final String secretFinal = secret;
        HttpClientErrorException ex = assertThrows(
                HttpClientErrorException.class,
                () -> restTemplate.exchange(
                        EXTRACT_ENDPOINT, HttpMethod.POST,
                        buildRequest(secretFinal), String.class),
                "Flask must reject the corrupt image but NOT for auth reasons");

        // ── Core assertion ───────────────────────────────────────────────────
        // 400 = image decode failure → secret was accepted
        // 401 = secret mismatch    → FACE_SERVICE_SECRET values differ
        assertNotEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode(),
                "HTTP 401 here means FACE_SERVICE_SECRET does not match between Spring Boot and Flask");
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode(),
                "Expected HTTP 400 (image decode failure): secret was accepted by Flask");

        String responseBody = ex.getResponseBodyAsString();
        assertTrue(responseBody.contains("extracted"),
                "Response body must contain the 'extracted' field");
        assertFalse(responseBody.contains("\"extracted\":true")
                        || responseBody.contains("\"extracted\": true"),
                "extracted must be false (corrupt image was not decoded)");

        // ── Spring Boot error-handling chain verification ────────────────────
        // FaceRegistrationService.callFlaskExtract() catches HttpClientErrorException
        // for any 4xx and throws: new IllegalArgumentException("Face image could not be processed")
        // FaceRegistrationController catches that and returns HTTP 400 with the fixed generic message.
        assertTrue(ex.getStatusCode().is4xxClientError(),
                "Exception is in the 4xx family — matches HttpClientErrorException catch block in service");

        System.out.println("[T3 PASS] Correct secret → HTTP 400 (auth accepted; image decode failed).");
        System.out.println("[T3]  Spring Boot path: catch(HttpClientErrorException) → " +
                "IllegalArgumentException(\"Face image could not be processed\") → HTTP 400 to Android");
    }
}
