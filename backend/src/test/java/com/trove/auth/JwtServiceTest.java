/*
 * ============================================================================
 *  JwtServiceTest — issue/verify round-trip and rejection of bad tokens
 * ============================================================================
 *  Purpose:        proves tokens carry the right identity, and that invalid or
 *                  expired tokens are rejected (returned as empty, not trusted).
 *  Design:         pure JUnit, no Spring context. Uses a >=32-byte dev secret.
 * ============================================================================
 */
package com.trove.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private JwtService serviceWithExpiry(long minutes) {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-that-is-at-least-32-bytes-long!!");
        props.setExpirationMinutes(minutes);
        return new JwtService(props);
    }

    @Test
    void issuedTokenParsesBackToSamePrincipal() {
        JwtService service = serviceWithExpiry(60);
        UUID userId = UUID.randomUUID();

        String token = service.issue(userId, "dev@trove.local", "Dev User");
        Optional<AuthUser> parsed = service.parse(token);

        assertTrue(parsed.isPresent());
        assertEquals(userId, parsed.get().id());
        assertEquals("dev@trove.local", parsed.get().email());
    }

    @Test
    void garbageTokenIsRejected() {
        JwtService service = serviceWithExpiry(60);
        assertTrue(service.parse("not-a-real-token").isEmpty());
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService service = serviceWithExpiry(-1); // already expired
        String token = service.issue(UUID.randomUUID(), "dev@trove.local", "Dev User");
        assertTrue(service.parse(token).isEmpty());
    }
}
