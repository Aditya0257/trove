/*
 * ============================================================================
 *  JwtService — issues and verifies stateless JWT access tokens
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Creates signed JWTs on login (subject = user id, with email/name claims) and
 *  parses/validates them on each request.
 *
 *  Business use case
 *  -----------------
 *  Stateless auth is what lets the host stay disposable: any instance can validate
 *  a token with just the shared secret — no session store, no sticky sessions.
 *
 *  Solution architecture
 *  ---------------------
 *  HS256 (symmetric) via jjwt. Used by AuthController (issue) and
 *  JwtAuthenticationFilter (verify). Secret + lifetime come from JwtProperties.
 *
 *  Reasoning & logic
 *  -----------------
 *  Symmetric signing is the right fit for a single-service backend (no need for
 *  asymmetric keys until other services must verify tokens independently). Parsing
 *  failures (expired/invalid) return empty so the filter can reject cleanly.
 * ============================================================================
 */
package com.trove.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final JwtProperties props;
    private final SecretKey key;

    public JwtService(JwtProperties props) {
        this.props = props;
        if (props.getSecret() == null || props.getSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "trove.security.jwt.secret must be set and at least 32 bytes long");
        }
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** Issues a signed token for the given user. */
    public String issue(UUID userId, String email, String displayName) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.getExpirationMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("name", displayName)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    /** Parses and validates a token, returning the principal or empty if invalid/expired. */
    public Optional<AuthUser> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new AuthUser(UUID.fromString(claims.getSubject()),
                    claims.get("email", String.class)));
        } catch (Exception e) {
            log.debug("Rejected JWT: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
