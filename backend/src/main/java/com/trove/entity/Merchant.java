/*
 * ============================================================================
 *  Merchant — canonical vendor identity
 * ============================================================================
 *  Purpose:        maps the `merchant` table (DESIGN.md §2): one row per real-world
 *                  vendor under a single canonical name.
 *  Business use:    "all Nike purchases" needs one stable merchant even though OCR
 *                  reads many raw spellings ("NIKE STORE", "nike.com").
 *  Design:         created_at populated by Hibernate @CreationTimestamp so the
 *                  INSERT carries a value (avoids clashing with the DB default).
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
@Table(name = "merchant")
public class Merchant extends BaseEntity {

    @Column(name = "canonical_name", nullable = false, unique = true)
    private String canonicalName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Merchant() {
        // for JPA
    }

    public Merchant(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
