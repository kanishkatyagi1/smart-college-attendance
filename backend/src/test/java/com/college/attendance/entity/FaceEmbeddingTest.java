package com.college.attendance.entity;

import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FaceEmbeddingTest {

    @Test
    void builderWithoutExplicitTimestampsProducesNonNullTimestamps() {
        Student student = Student.builder().id(1L).build();
        byte[] nonce = new byte[12];
        byte[] ciphertext = new byte[512];
        byte[] tag = new byte[16];

        FaceEmbedding fe = FaceEmbedding.builder()
                .student(student)
                .nonce(nonce)
                .ciphertext(ciphertext)
                .tag(tag)
                .build();

        assertNotNull(fe.getCreatedAt(), "createdAt must not be null when using builder");
        assertNotNull(fe.getUpdatedAt(), "updatedAt must not be null when using builder");
        assertEquals("v1", fe.getEncryptionVersion(), "Default encryptionVersion must be v1");
    }

    @Test
    void builderDefaultEncryptionVersionIsV1() {
        FaceEmbedding fe = FaceEmbedding.builder().build();
        assertEquals("v1", fe.getEncryptionVersion());
    }

    @Test
    void builderExplicitlySuppliedValuesArePreserved() {
        Instant customCreated = Instant.parse("2026-01-01T00:00:00Z");
        Instant customUpdated = Instant.parse("2026-01-02T00:00:00Z");

        FaceEmbedding fe = FaceEmbedding.builder()
                .encryptionVersion("v2")
                .createdAt(customCreated)
                .updatedAt(customUpdated)
                .build();

        assertEquals("v2", fe.getEncryptionVersion());
        assertEquals(customCreated, fe.getCreatedAt());
        assertEquals(customUpdated, fe.getUpdatedAt());
    }

    @Test
    void noArgsConstructorInitializesDefaults() {
        FaceEmbedding fe = new FaceEmbedding();
        assertNotNull(fe.getCreatedAt());
        assertNotNull(fe.getUpdatedAt());
        assertEquals("v1", fe.getEncryptionVersion());
    }

    @Test
    void prePersistInitializesNullFields() {
        FaceEmbedding fe = new FaceEmbedding();
        fe.setCreatedAt(null);
        fe.setUpdatedAt(null);
        fe.setEncryptionVersion(null);

        fe.onCreate(); // simulates @PrePersist

        assertNotNull(fe.getCreatedAt(), "prePersist must populate null createdAt");
        assertNotNull(fe.getUpdatedAt(), "prePersist must populate null updatedAt");
        assertEquals("v1", fe.getEncryptionVersion(), "prePersist must populate null encryptionVersion");
    }

    @Test
    void preUpdateRefreshesUpdatedAt() {
        FaceEmbedding fe = FaceEmbedding.builder().build();
        Instant initial = Instant.parse("2026-01-01T00:00:00Z");
        fe.setUpdatedAt(initial);

        fe.onUpdate(); // simulates @PreUpdate

        assertTrue(fe.getUpdatedAt().isAfter(initial), "preUpdate must refresh updatedAt timestamp");
    }
}
