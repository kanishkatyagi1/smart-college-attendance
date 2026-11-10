package com.college.attendance.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private static final String SECRET = "test-secret-that-is-long-enough-for-hmac-sha-256";
    private JwtService jwt;

    @BeforeEach void setUp() {
        jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "secret", SECRET);
        ReflectionTestUtils.setField(jwt, "expiration", 60_000L);
    }

    @Test void generatesSignedTokenWithIdentityAndRoleClaims() {
        var claims = jwt.parse(jwt.generate(7L, "student@example.test", "STUDENT"));
        assertEquals("student@example.test", claims.getSubject());
        assertEquals(7, ((Number) claims.get("uid")).intValue());
        assertEquals("STUDENT", claims.get("role", String.class));
    }

    @Test void rejectsMalformedAndExpiredTokens() {
        assertThrows(Exception.class, () -> jwt.parse("not-a-jwt"));
        String expired = Jwts.builder().subject("student@example.test").claim("uid", 7L).claim("role", "STUDENT")
                .expiration(new Date(System.currentTimeMillis() - 1_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        assertThrows(ExpiredJwtException.class, () -> jwt.parse(expired));
    }
}
