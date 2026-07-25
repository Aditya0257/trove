/*
 * ============================================================================
 *  EmailVerification — the one-time code a new user must enter to prove email
 * ============================================================================
 *  Purpose:        maps the email_verification table (V24): one active OTP per
 *                  user, stored only as a SHA-256 hash, with an expiry and a
 *                  capped attempt count.
 *  Design:         user_id is the primary key (one row per user); a resend
 *                  overwrites the row. Never stores the plaintext code.
 * ============================================================================
 */
package com.trove.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verification")
public class EmailVerification {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected EmailVerification() {
        // for JPA
    }

    public EmailVerification(UUID userId, String codeHash, Instant expiresAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public String getCodeHash() { return codeHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
}
