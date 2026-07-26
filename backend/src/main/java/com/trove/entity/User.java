/*
 * ============================================================================
 *  User — an account (maps app_user)
 * ============================================================================
 *  Purpose:        the identity entity: email, display name, BCrypt password hash.
 *  Business use:    every user has this account + a personal space; they may also
 *                  join shared spaces.
 *  Design:         password stored only as a BCrypt hash. created_at managed by
 *                  Hibernate. Extends BaseEntity (app-assigned UUID).
 * ============================================================================
 */
package com.trove.entity;

import com.trove.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "app_user")
public class User extends BaseEntity {

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // TOTP two-factor: the Base32 secret AES-GCM encrypted at rest, and whether the user
    // has finished enrolling (verified a code). Null/false means 2FA is off for this user.
    @Column(name = "totp_secret_enc")
    private String totpSecretEnc;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled = false;

    // Account lifecycle for closed registration: 'active' (can sign in), 'pending'
    // (awaiting admin approval), or 'rejected'. Existing accounts default to active.
    @Column(name = "status", nullable = false)
    private String status = "active";

    // Optional profile photo: the R2 object key of the stored image (never the bytes).
    // Null means no photo and the UI falls back to the user's initials.
    @Column(name = "avatar_key")
    private String avatarKey;

    // A new sign-in email awaiting OTP confirmation during an email change. The `email`
    // column stays authoritative until the code is verified, then this is promoted + cleared.
    @Column(name = "pending_email")
    private String pendingEmail;

    protected User() {
        // for JPA
    }

    public User(String email, String displayName, String passwordHash) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getAvatarKey() { return avatarKey; }
    public void setAvatarKey(String avatarKey) { this.avatarKey = avatarKey; }
    public String getPendingEmail() { return pendingEmail; }
    public void setPendingEmail(String pendingEmail) { this.pendingEmail = pendingEmail; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getTotpSecretEnc() { return totpSecretEnc; }
    public void setTotpSecretEnc(String totpSecretEnc) { this.totpSecretEnc = totpSecretEnc; }
    public boolean isTotpEnabled() { return totpEnabled; }
    public void setTotpEnabled(boolean totpEnabled) { this.totpEnabled = totpEnabled; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
}
