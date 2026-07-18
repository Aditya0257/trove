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
package com.trove.auth;

import com.trove.common.BaseEntity;
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

    protected User() {
        // for JPA
    }

    public User(String email, String displayName, String passwordHash) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public Instant getCreatedAt() { return createdAt; }
}
