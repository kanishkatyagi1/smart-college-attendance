package com.college.attendance.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EncryptionUtil.
 *
 * Key-loading strategy used here:
 *   - System property "face.emb.key" is set to a known 32-byte test key before each test.
 *   - System property "face.emb.key.useEnv" is set to "false" so the env variable is never
 *     consulted, making every test fully deterministic regardless of the shell environment.
 *   - Tests that need to simulate missing / bad configuration temporarily clear or change
 *     "face.emb.key" and restore it in @AfterEach.
 */
class EncryptionUtilTest {

    // 32 zero bytes, Base64-encoded — a valid AES-256 test key (never used in production).
    static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @BeforeEach
    void setTestKey() {
        System.setProperty("face.emb.key", TEST_KEY);
        System.setProperty("face.emb.key.useEnv", "false");
    }

    @AfterEach
    void restoreTestKey() {
        System.setProperty("face.emb.key", TEST_KEY);
        System.setProperty("face.emb.key.useEnv", "false");
    }

    // ── 1. Round-trip ────────────────────────────────────────────────────────

    @Test
    void encryptDecryptRoundTrip() throws Exception {
        byte[] plain = "test-payload".getBytes();
        EncryptionUtil.Ciphertext ct = EncryptionUtil.encrypt(plain);

        assertNotNull(ct,                                   "Ciphertext must not be null");
        assertEquals(12, ct.nonce.length,                  "Nonce must be 12 bytes (GCM IV)");
        assertEquals(16, ct.tag.length,                    "Tag must be 16 bytes (128-bit GCM tag)");
        assertNotNull(ct.ciphertext,                        "Ciphertext body must not be null");

        byte[] recovered = EncryptionUtil.decrypt(ct);
        assertArrayEquals(plain, recovered,                 "Decrypted bytes must equal original plaintext");
    }

    // ── 2. Nonce uniqueness ──────────────────────────────────────────────────

    @Test
    void noncesAreUniqueAcrossCalls() throws Exception {
        EncryptionUtil.Ciphertext ct1 = EncryptionUtil.encrypt("message-one".getBytes());
        EncryptionUtil.Ciphertext ct2 = EncryptionUtil.encrypt("message-two".getBytes());
        assertFalse(Arrays.equals(ct1.nonce, ct2.nonce),
                "Each encryption call must generate a fresh 12-byte nonce");
    }

    // ── 3. Ciphertext tampering ──────────────────────────────────────────────

    @Test
    void tamperingCiphertextFailsDecryption() throws Exception {
        EncryptionUtil.Ciphertext ct = EncryptionUtil.encrypt("important-data".getBytes());
        ct.ciphertext[0] ^= 0xFF; // flip all bits in the first byte
        assertThrows(Exception.class, () -> EncryptionUtil.decrypt(ct),
                "GCM authentication must reject a modified ciphertext body");
    }

    // ── 4. Tag tampering ─────────────────────────────────────────────────────

    @Test
    void tamperingTagFailsDecryption() throws Exception {
        EncryptionUtil.Ciphertext ct = EncryptionUtil.encrypt("important-data".getBytes());
        ct.tag[0] ^= 0xFF; // corrupt the first byte of the authentication tag
        assertThrows(Exception.class, () -> EncryptionUtil.decrypt(ct),
                "GCM authentication must reject a modified authentication tag");
    }

    // ── 5. Invalid Base64 key ────────────────────────────────────────────────

    @Test
    void invalidBase64KeyIsRejected() {
        System.setProperty("face.emb.key", "@@@not-valid-base64@@@");
        // Base64.Decoder.decode() throws IllegalArgumentException for bad input.
        assertThrows(IllegalArgumentException.class, EncryptionUtil::loadKey,
                "A key string that is not valid Base64 must be rejected");
    }

    // ── 6. Wrong key length ──────────────────────────────────────────────────

    @Test
    void keyWithWrongLengthIsRejected() {
        // 16 bytes = AES-128, not AES-256; must be rejected.
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        System.setProperty("face.emb.key", shortKey);
        assertThrows(IllegalStateException.class, EncryptionUtil::loadKey,
                "A key that does not decode to exactly 32 bytes must be rejected");
    }

    // ── 7. Missing key configuration ─────────────────────────────────────────

    @Test
    void missingKeyConfigurationIsRejected() {
        // Clear the system property; env fallback is already disabled by @BeforeEach.
        System.clearProperty("face.emb.key");
        assertThrows(IllegalStateException.class, EncryptionUtil::loadKey,
                "Missing key configuration must throw IllegalStateException");
    }
}
