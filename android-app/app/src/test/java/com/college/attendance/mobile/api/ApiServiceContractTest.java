package com.college.attendance.mobile.api;

import com.college.attendance.mobile.model.ActiveLivenessSession;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import static org.junit.Assert.*;

/**
 * Retrofit API contract test for {@link ApiService#submitLivenessFrame}.
 *
 * <p>Uses {@link MockWebServer} so Retrofit actually parses the method
 * annotations and builds a real HTTP request — annotation reflection alone
 * cannot catch the {@code @Part("name")} + {@code MultipartBody.Part}
 * conflict that caused the production crash.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>No {@link IllegalArgumentException} when calling the method (the crash).</li>
 *   <li>Correct POST URL path and query parameter.</li>
 *   <li>Content-Type is multipart/form-data.</li>
 *   <li>Part name is "frame".</li>
 *   <li>JPEG filename is "frame.jpg".</li>
 *   <li>Content-Type of the part is image/jpeg.</li>
 *   <li>JPEG bytes are transmitted without corruption.</li>
 * </ul>
 */
public class ApiServiceContractTest {

    private MockWebServer server;
    private ApiService api;

    // Minimal valid JSON response for ActiveLivenessSession
    private static final String SESSION_JSON =
            "{\"sessionId\":\"test-id\",\"state\":\"ACTIVE\","
          + "\"challenge\":[\"BLINK\"],\"nextExpectedSequence\":1,"
          + "\"retryCount\":0,\"purpose\":\"VERIFICATION\","
          + "\"expiresAt\":\"2026-11-24T20:00:00Z\",\"reason\":null}";

    // Synthetic JPEG bytes (valid SOI + EOI markers)
    private static final byte[] FAKE_JPEG = new byte[]{
            (byte) 0xFF, (byte) 0xD8,  // SOI
            (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,  // APP0 marker + length
            'J','F','I','F', 0x00,
            0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xD9   // EOI
    };

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        OkHttpClient client = new OkHttpClient.Builder().build();
        api = new Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService.class);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    // -----------------------------------------------------------------------
    // Helper: build the same MultipartBody.Part that ActiveLivenessActivity
    // constructs in submitFrame()
    // -----------------------------------------------------------------------

    private static MultipartBody.Part framePart(byte[] jpeg) {
        RequestBody rb = RequestBody.create(jpeg, MediaType.parse("image/jpeg"));
        return MultipartBody.Part.createFormData("frame", "frame.jpg", rb);
    }

    // -----------------------------------------------------------------------
    // Test 1: No crash — Retrofit accepts the annotation without @Part("name")
    // -----------------------------------------------------------------------

    @Test
    public void submitLivenessFrame_doesNotThrowIllegalArgumentException()
            throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(SESSION_JSON)
                .addHeader("Content-Type", "application/json"));

        // This call must not throw IllegalArgumentException at request-creation time.
        Call<ActiveLivenessSession> call = api.submitLivenessFrame(
                "session-uuid-123", 0L, framePart(FAKE_JPEG));
        assertNotNull("Retrofit must create the Call without exception", call);

        Response<ActiveLivenessSession> response = call.execute();
        assertTrue("HTTP 200 expected", response.isSuccessful());
    }

    // -----------------------------------------------------------------------
    // Test 2: Correct URL path and sequence query parameter
    // -----------------------------------------------------------------------

    @Test
    public void submitLivenessFrame_correctUrlAndSequence() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(SESSION_JSON)
                .addHeader("Content-Type", "application/json"));

        api.submitLivenessFrame("my-session-id", 42L, framePart(FAKE_JPEG))
           .execute();

        RecordedRequest req = server.takeRequest();
        String path = req.getPath();
        assertNotNull(path);
        assertTrue("Path must contain session ID",
                path.contains("my-session-id"));
        assertTrue("Path must start with active-liveness frames endpoint",
                path.startsWith("/api/face/active-liveness/sessions/my-session-id/frames"));
        assertTrue("Query must contain sequence=42",
                path.contains("sequence=42"));
    }

    // -----------------------------------------------------------------------
    // Test 3: Request is multipart/form-data
    // -----------------------------------------------------------------------

    @Test
    public void submitLivenessFrame_contentTypeIsMultipart() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(SESSION_JSON)
                .addHeader("Content-Type", "application/json"));

        api.submitLivenessFrame("s1", 0L, framePart(FAKE_JPEG)).execute();

        RecordedRequest req = server.takeRequest();
        String ct = req.getHeader("Content-Type");
        assertNotNull("Content-Type header must be present", ct);
        assertTrue("Content-Type must be multipart/form-data",
                ct.startsWith("multipart/form-data"));
    }

    // -----------------------------------------------------------------------
    // Test 4: Part name is "frame" (embedded by createFormData, not by @Part)
    // -----------------------------------------------------------------------

    @Test
    public void submitLivenessFrame_partNameIsFrame() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(SESSION_JSON)
                .addHeader("Content-Type", "application/json"));

        api.submitLivenessFrame("s2", 1L, framePart(FAKE_JPEG)).execute();

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readString(StandardCharsets.UTF_8);
        assertTrue("Multipart body must contain part name 'frame'",
                body.contains("name=\"frame\""));
    }

    // -----------------------------------------------------------------------
    // Test 5: Filename is frame.jpg
    // -----------------------------------------------------------------------

    @Test
    public void submitLivenessFrame_filenameIsFrameJpg() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(SESSION_JSON)
                .addHeader("Content-Type", "application/json"));

        api.submitLivenessFrame("s3", 2L, framePart(FAKE_JPEG)).execute();

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readString(StandardCharsets.UTF_8);
        assertTrue("Multipart body must contain filename=\"frame.jpg\"",
                body.contains("filename=\"frame.jpg\""));
    }

    // -----------------------------------------------------------------------
    // Test 6: Part Content-Type is image/jpeg
    // -----------------------------------------------------------------------

    @Test
    public void submitLivenessFrame_partContentTypeIsImageJpeg() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(SESSION_JSON)
                .addHeader("Content-Type", "application/json"));

        api.submitLivenessFrame("s4", 3L, framePart(FAKE_JPEG)).execute();

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readString(StandardCharsets.UTF_8);
        assertTrue("Multipart body must declare Content-Type: image/jpeg",
                body.contains("Content-Type: image/jpeg"));
    }

    // -----------------------------------------------------------------------
    // Test 7: JPEG bytes are transmitted (SOI marker present in body)
    // -----------------------------------------------------------------------

    @Test
    public void submitLivenessFrame_jpegBytesTransmitted() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(SESSION_JSON)
                .addHeader("Content-Type", "application/json"));

        api.submitLivenessFrame("s5", 4L, framePart(FAKE_JPEG)).execute();

        RecordedRequest req = server.takeRequest();
        // The raw body is binary (multipart); read as bytes and check for
        // the JPEG SOI marker 0xFF 0xD8.
        byte[] rawBody = req.getBody().readByteArray();
        boolean soiFound = false;
        for (int i = 0; i < rawBody.length - 1; i++) {
            if ((rawBody[i] & 0xFF) == 0xFF && (rawBody[i + 1] & 0xFF) == 0xD8) {
                soiFound = true;
                break;
            }
        }
        assertTrue("JPEG SOI marker (0xFF 0xD8) must be present in transmitted bytes",
                soiFound);
    }

    // -----------------------------------------------------------------------
    // Test 8: HTTP method is POST
    // -----------------------------------------------------------------------

    @Test
    public void submitLivenessFrame_httpMethodIsPost() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(SESSION_JSON)
                .addHeader("Content-Type", "application/json"));

        api.submitLivenessFrame("s6", 5L, framePart(FAKE_JPEG)).execute();

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
    }

    // -----------------------------------------------------------------------
    // Test 9: Response is deserialized into ActiveLivenessSession
    // -----------------------------------------------------------------------

    @Test
    public void submitLivenessFrame_responseDeserializesCorrectly()
            throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(SESSION_JSON)
                .addHeader("Content-Type", "application/json"));

        Response<ActiveLivenessSession> resp =
                api.submitLivenessFrame("s7", 0L, framePart(FAKE_JPEG)).execute();

        assertTrue(resp.isSuccessful());
        assertNotNull(resp.body());
        assertEquals("test-id", resp.body().sessionId);
        assertEquals("ACTIVE", resp.body().state);
        assertEquals(1L, resp.body().nextExpectedSequence);
        assertNotNull(resp.body().challenge);
        assertFalse(resp.body().challenge.isEmpty());
        assertEquals("BLINK", resp.body().challenge.get(0));
    }

    // -----------------------------------------------------------------------
    // Test 10: Sequence 0 is transmitted (edge case — first frame)
    // -----------------------------------------------------------------------

    @Test
    public void submitLivenessFrame_sequenceZeroTransmitted() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(SESSION_JSON)
                .addHeader("Content-Type", "application/json"));

        api.submitLivenessFrame("s8", 0L, framePart(FAKE_JPEG)).execute();

        RecordedRequest req = server.takeRequest();
        assertTrue("sequence=0 must appear in URL for the first frame",
                req.getPath().contains("sequence=0"));
    }
}
