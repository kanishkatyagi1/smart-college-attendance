package com.college.attendance.controller;

import com.college.attendance.service.FaceRegistrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link FaceRegistrationController}.
 *
 * <p>No Spring context is started; the controller is instantiated directly
 * with a Mockito-mocked service. Response bodies are {@link FaceRegistrationResponse}
 * DTOs serialised by Jackson (S-5), not raw strings.
 */
class FaceRegistrationControllerTest {

    private FaceRegistrationService faceService;
    private FaceRegistrationController controller;

    @BeforeEach
    void setUp() {
        faceService  = mock(FaceRegistrationService.class);
        controller   = new FaceRegistrationController(faceService);

        // Simulate an authenticated STUDENT principal
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "student@gbu.ac.in", null,
                        List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ── Helper: cast body to DTO ──────────────────────────────────────────────

    private static FaceRegistrationResponse body(ResponseEntity<?> resp) {
        assertNotNull(resp.getBody(), "Response body must not be null");
        assertInstanceOf(FaceRegistrationResponse.class, resp.getBody(),
                "Response body must be a FaceRegistrationResponse DTO (S-5)");
        return (FaceRegistrationResponse) resp.getBody();
    }

    // ── 1. Successful multipart registration ──────────────────────────────────

    @Test
    void successfulRegistrationReturnsRegisteredTrue() throws Exception {
        doNothing().when(faceService).registerFace(anyString(), any(byte[].class));

        MockMultipartFile image = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", image);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        FaceRegistrationResponse dto = body(response);
        // Android FaceCaptureActivity checks result.registered == true
        assertTrue(dto.isRegistered(), "registered must be true on success");
        assertNotNull(dto.getMessage(), "success response must include a message");
        assertNull(dto.getError(),      "success response must not include an error");
    }

    // ── 2. Empty image is rejected before service is called ───────────────────

    @Test
    void emptyImageReturnsBadRequest() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[0]);

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", empty);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        FaceRegistrationResponse dto = body(response);
        assertFalse(dto.isRegistered());
        verify(faceService, never()).registerFace(anyString(), any());
    }

    // ── 3. Flask validation failure (bad image / no face) → 400 ──────────────
    // S-4: response body must not expose Flask HTTP status codes.

    @Test
    void flaskValidationFailureReturnsBadRequestWithGenericMessage() throws Exception {
        // S-4: service now throws a generic message, never "HTTP 400"
        doThrow(new IllegalArgumentException("Face image could not be processed"))
                .when(faceService).registerFace(anyString(), any(byte[].class));

        MockMultipartFile image = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", image);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        FaceRegistrationResponse dto = body(response);
        assertFalse(dto.isRegistered());
        // S-4: error must not leak upstream HTTP status code
        assertNotNull(dto.getError());
        assertFalse(dto.getError().contains("HTTP 400"),
                "S-4: error must not expose Flask HTTP status codes to the client");
        assertFalse(dto.getError().contains("HTTP 401"),
                "S-4: error must not expose Flask HTTP status codes to the client");
    }

    // ── 4a. Sequential duplicate registration → 409 ───────────────────────────

    @Test
    void sequentialDuplicateRegistrationReturnsConflict() throws Exception {
        doThrow(new IllegalStateException("Face already registered"))
                .when(faceService).registerFace(anyString(), any(byte[].class));

        MockMultipartFile image = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", image);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        FaceRegistrationResponse dto = body(response);
        assertFalse(dto.isRegistered());
        assertNotNull(dto.getError());
    }

    // ── 4b. Concurrent duplicate registration (S-3) → 409, not 500 ───────────
    // The DB UNIQUE constraint fires concurrently and throws DataIntegrityViolationException.
    // The @Transactional rollback has completed before the exception reaches the controller.

    @Test
    void concurrentDuplicateRegistrationReturns409NotInternalServerError() throws Exception {
        // Simulate the DB UNIQUE constraint firing from a concurrent second save()
        doThrow(new DataIntegrityViolationException("UK_face_embeddings_student constraint violated"))
                .when(faceService).registerFace(anyString(), any(byte[].class));

        MockMultipartFile image = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", image);

        // S-3: must return 409 Conflict, not 500 Internal Server Error
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode(),
                "S-3: concurrent duplicate must return HTTP 409, not 500");
        FaceRegistrationResponse dto = body(response);
        assertFalse(dto.isRegistered(),
                "registered must be false on duplicate");
        assertNotNull(dto.getError(),
                "error message must be present");
        // Error text must be client-safe — no raw constraint names or DB internals
        assertFalse(dto.getError().contains("UK_face_embeddings"),
                "S-3: DB constraint name must not be exposed to client");
        assertFalse(dto.getError().contains("DataIntegrityViolation"),
                "S-3: Spring exception class name must not be exposed to client");
    }

    @Test
    void concurrentDuplicateRegistrationWithHibernateConstraintViolationReturns409() throws Exception {
        // Simulate genuine MySQL error code 1062 duplicate key on student_id
        SQLException sqlEx = new SQLException("Duplicate entry '10' for key 'face_embeddings.student_id'", "23000", 1062);
        ConstraintViolationException cve = new ConstraintViolationException(
                "could not execute statement", sqlEx, "UK_face_embeddings_student");
        DataIntegrityViolationException dive = new DataIntegrityViolationException(
                "could not execute statement; SQL [n/a]; constraint [UK_face_embeddings_student]", cve);

        doThrow(dive).when(faceService).registerFace(anyString(), any(byte[].class));

        MockMultipartFile image = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", image);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        FaceRegistrationResponse dto = body(response);
        assertFalse(dto.isRegistered());
        assertEquals("Face already registered", dto.getError());
    }

    @Test
    void nonDuplicateDataIntegrityViolationReturns500Not409() throws Exception {
        // Simulate null column integrity violation (e.g., Column 'created_at' cannot be null)
        DataIntegrityViolationException dive = new DataIntegrityViolationException(
                "could not execute statement; SQL [n/a]; Column 'created_at' cannot be null");

        doThrow(dive).when(faceService).registerFace(anyString(), any(byte[].class));

        MockMultipartFile image = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", image);

        // Must return 500 Internal Server Error, NOT 409 Conflict
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        FaceRegistrationResponse dto = body(response);
        assertFalse(dto.isRegistered());
        assertNotNull(dto.getError());
        assertEquals("Internal server error", dto.getError());
        assertFalse(dto.getError().contains("created_at"),
                "Must not leak column name");
    }

    @Test
    void propertyValueNullIntegrityViolationReturns500Not409() throws Exception {
        // Simulate Hibernate PropertyValueException wrapped in DataIntegrityViolationException
        DataIntegrityViolationException dive = new DataIntegrityViolationException(
                "not-null property references a null or transient value : com.college.attendance.entity.FaceEmbedding.createdAt");

        doThrow(dive).when(faceService).registerFace(anyString(), any(byte[].class));

        MockMultipartFile image = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", image);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        FaceRegistrationResponse dto = body(response);
        assertFalse(dto.isRegistered());
        assertEquals("Internal server error", dto.getError());
    }

    @Test
    void foreignKeyIntegrityViolationReturns500Not409() throws Exception {
        // Simulate foreign key integrity violation
        DataIntegrityViolationException dive = new DataIntegrityViolationException(
                "Cannot add or update a child row: a foreign key constraint fails");

        doThrow(dive).when(faceService).registerFace(anyString(), any(byte[].class));

        MockMultipartFile image = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", image);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        FaceRegistrationResponse dto = body(response);
        assertFalse(dto.isRegistered());
        assertEquals("Internal server error", dto.getError());
    }

    // ── 5. Face service unavailable → 503 ────────────────────────────────────

    @Test
    void serviceUnavailableReturns503() throws Exception {
        doThrow(new IllegalStateException("Face service unavailable"))
                .when(faceService).registerFace(anyString(), any(byte[].class));

        MockMultipartFile image = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", image);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        FaceRegistrationResponse dto = body(response);
        assertFalse(dto.isRegistered());
    }

    // ── 6. Inactive / not approved student → 403 ─────────────────────────────

    @Test
    void inactiveStudentReturnsForbidden() throws Exception {
        doThrow(new IllegalStateException("Student not active or not approved"))
                .when(faceService).registerFace(anyString(), any(byte[].class));

        MockMultipartFile image = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", image);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        FaceRegistrationResponse dto = body(response);
        assertFalse(dto.isRegistered());
    }

    // ── 7. S-5: response body is always a DTO, never a raw String ────────────

    @Test
    void allResponseBodiesAreStructuredDtoNotRawStrings() throws Exception {
        // Test every response path: success, bad-request, conflict, 503, 403, 500
        doNothing().when(faceService).registerFace(anyString(), any(byte[].class));
        MockMultipartFile img = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseEntity<?> ok = controller.register("student@gbu.ac.in", img);
        assertInstanceOf(FaceRegistrationResponse.class, ok.getBody(),
                "S-5: success body must be FaceRegistrationResponse, not String");

        doThrow(new DataIntegrityViolationException("dup")).when(faceService)
                .registerFace(anyString(), any(byte[].class));
        ResponseEntity<?> conflict = controller.register("student@gbu.ac.in", img);
        assertInstanceOf(FaceRegistrationResponse.class, conflict.getBody(),
                "S-5: conflict body must be FaceRegistrationResponse, not String");

        doThrow(new IllegalArgumentException("bad")).when(faceService)
                .registerFace(anyString(), any(byte[].class));
        ResponseEntity<?> badReq = controller.register("student@gbu.ac.in", img);
        assertInstanceOf(FaceRegistrationResponse.class, badReq.getBody(),
                "S-5: bad-request body must be FaceRegistrationResponse, not String");
    }

    // ── 8. G-2: IOException from image.getBytes() → 500, no details leaked ───
    // Verifies that an I/O failure while reading the uploaded file bytes is
    // handled by the catch (IOException) block and returns HTTP 500 with a
    // generic message — no IOException message or stack trace reaches the client.

    @Test
    void imageReadIoExceptionReturns500WithGenericMessage() throws Exception {
        // Use a Mockito mock of MultipartFile so getBytes() can throw IOException
        MultipartFile brokenImage = mock(MultipartFile.class);
        when(brokenImage.isEmpty()).thenReturn(false);
        when(brokenImage.getBytes()).thenThrow(new IOException("disk read error"));

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", brokenImage);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(),
                "G-2: IOException from getBytes() must return HTTP 500");
        FaceRegistrationResponse dto = body(response);
        assertFalse(dto.isRegistered(), "registered must be false");
        // G-2: internal IOException message must not reach the client
        assertNotNull(dto.getError(), "error field must be present");
        assertFalse(dto.getError().contains("disk read error"),
                "G-2: IOException detail must not be exposed to the client");
        assertFalse(dto.getError().contains("IOException"),
                "G-2: Exception class name must not be exposed to the client");
        // Service must never be called — the failure is in image.getBytes()
        verify(faceService, never()).registerFace(anyString(), any());
    }

    // ── 9. G-3: Unexpected RuntimeException → 500, no details leaked ──────────
    // Verifies that an unexpected non-HTTP RuntimeException from the service
    // (e.g. a codec failure, transient infrastructure error) is caught by the
    // final catch (Exception) block and returns HTTP 500 with the fixed generic
    // message — no internal exception class name or message reaches the client.

    @Test
    void unexpectedRuntimeExceptionReturns500WithGenericMessage() throws Exception {
        doThrow(new RuntimeException("unexpected codec failure"))
                .when(faceService).registerFace(anyString(), any(byte[].class));

        MockMultipartFile image = new MockMultipartFile(
                "image", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ResponseEntity<?> response = controller.register("student@gbu.ac.in", image);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(),
                "G-3: Unexpected RuntimeException must return HTTP 500");
        FaceRegistrationResponse dto = body(response);
        assertFalse(dto.isRegistered(), "registered must be false");
        // G-3: internal exception details must not reach the client
        assertNotNull(dto.getError(), "error field must be present");
        assertFalse(dto.getError().contains("codec"),
                "G-3: Internal exception message must not be exposed to the client");
        assertFalse(dto.getError().contains("RuntimeException"),
                "G-3: Exception class name must not be exposed to the client");
        assertEquals("Internal server error", dto.getError(),
                "G-3: 500 response must use the fixed generic error string");
    }
}
