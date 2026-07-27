/*
 * ============================================================================
 *  IngestToken — a space's unguessable forward-to-file token
 * ============================================================================
 *  Purpose:        maps `ingest_token` (one per space): the token embedded in that
 *                  space's ingest address.
 *  Business use:    lets a forwarded document be routed to a space by token alone —
 *                  no shared secret + spaceId needed.
 *  Design:         space_id is the primary key (one token per space); token is unique.
 * ============================================================================
 */
package com.trove.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingest_token")
public class IngestToken {

    @Id
    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    @Column(name = "token", nullable = false, unique = true)
    private String token;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IngestToken() {
        // for JPA
    }

    public IngestToken(UUID spaceId, String token) {
        this.spaceId = spaceId;
        this.token = token;
    }

    public UUID getSpaceId() { return spaceId; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Instant getCreatedAt() { return createdAt; }
}
