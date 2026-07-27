/*
 * ============================================================================
 *  JwtProperties — signing secret + token lifetime
 * ============================================================================
 *  Purpose:        binds trove.security.jwt.* (HMAC secret, expiry minutes).
 *  Business use:    stateless auth for a stateless host — a redeploy keeps working
 *                  because nothing about a session lives on the box.
 *  Design:         secret must be >= 32 bytes for HS256; supplied via env in prod
 *                  (never commit a real secret).
 * ============================================================================
 */
package com.trove.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.security.jwt")
public class JwtProperties {

    /** HMAC-SHA256 signing secret (>= 32 chars). Override via env in prod. */
    private String secret;

    /** Token lifetime in minutes. */
    private long expirationMinutes = 720; // 12h

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public long getExpirationMinutes() { return expirationMinutes; }
    public void setExpirationMinutes(long expirationMinutes) { this.expirationMinutes = expirationMinutes; }
}
