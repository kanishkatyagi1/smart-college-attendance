package com.college.attendance.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

public final class EncryptionUtil {
    private static final String ALG = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int GCM_IV_LENGTH = 12;   // bytes

    private EncryptionUtil() {}

    // Package-private: load and validate a raw Base64 key string.
    // Throws IllegalStateException for null/blank or wrong length,
    // IllegalArgumentException (from Base64) for invalid Base64.
    static SecretKey parseKey(String b64) {
        if (b64 == null || b64.isBlank()) {
            throw new IllegalStateException("Encryption key not configured");
        }
        byte[] decoded = Base64.getDecoder().decode(b64); // throws IllegalArgumentException on bad Base64
        if (decoded.length != 32) {
            throw new IllegalStateException("FACE_EMB_KEY must decode to exactly 32 bytes (got " + decoded.length + ")");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    // Package-private: load from system property or env. No caching — called per-operation.
    // System property "face.emb.key" takes priority (used in tests).
    // Falls back to FACE_EMB_KEY env variable unless "face.emb.key.useEnv" is explicitly "false".
    static SecretKey loadKey() {
        String prop = System.getProperty("face.emb.key");
        if (prop != null) {
            // Property is set (even if blank) — use it; parseKey will reject blank.
            return parseKey(prop);
        }
        // No system property set. Check if env fallback is disabled.
        String useEnv = System.getProperty("face.emb.key.useEnv");
        if ("false".equalsIgnoreCase(useEnv)) {
            return parseKey(null); // will throw IllegalStateException
        }
        // Default production path: read from environment variable.
        return parseKey(System.getenv("FACE_EMB_KEY"));
    }

    public static class Ciphertext {
        public final byte[] nonce;
        public final byte[] ciphertext;
        public final byte[] tag;

        public Ciphertext(byte[] nonce, byte[] ciphertext, byte[] tag) {
            this.nonce = nonce;
            this.ciphertext = ciphertext;
            this.tag = tag;
        }
    }

    public static Ciphertext encrypt(byte[] plaintext) throws Exception {
        SecretKey key = loadKey();
        Cipher cipher = Cipher.getInstance(ALG);
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);
        byte[] cipherBytes = cipher.doFinal(plaintext);
        // Java GCM appends the authentication tag at the end.
        int tagLen = GCM_TAG_LENGTH / 8; // 16 bytes
        int ctLen  = cipherBytes.length - tagLen;
        byte[] ct  = new byte[ctLen];
        byte[] tag = new byte[tagLen];
        System.arraycopy(cipherBytes, 0,     ct,  0,    ctLen);
        System.arraycopy(cipherBytes, ctLen, tag, 0,    tagLen);
        return new Ciphertext(iv, ct, tag);
    }

    public static byte[] decrypt(Ciphertext data) throws Exception {
        SecretKey key = loadKey();
        Cipher cipher = Cipher.getInstance(ALG);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, data.nonce);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        byte[] combined = new byte[data.ciphertext.length + data.tag.length];
        System.arraycopy(data.ciphertext, 0,                  combined, 0,                     data.ciphertext.length);
        System.arraycopy(data.tag,        0,                  combined, data.ciphertext.length, data.tag.length);
        return cipher.doFinal(combined);
    }
}
