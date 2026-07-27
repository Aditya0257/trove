/*
 * ============================================================================
 *  Space — a container that owns documents (personal or shared)
 * ============================================================================
 *  Purpose:        maps the `space` table (DESIGN.md §2). Every document belongs to
 *                  exactly one space.
 *  Business use:    a personal space is private to one user; a shared space lets a
 *                  household/friends/project keep common documents together.
 *  Design:         created_by stored as a plain UUID; created_at managed by Hibernate
 *                  so the INSERT carries a value.
 * ============================================================================
 */
package com.trove.entity;

import com.trove.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "space")
public class Space extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    /** Optional short bio/description the owner can set. */
    @Column(name = "description")
    private String description;

    @Column(name = "kind", nullable = false)
    private String kind; // personal | shared

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // How this space spreads its backup across its connected Drives: 'rotate' fills one
    // Drive then rolls to the next (aggregate capacity); 'mirror' copies every document
    // into all of them (redundant Tier-3 backup). See DriveSyncService.
    @Column(name = "drive_sync_mode", nullable = false)
    private String driveSyncMode = "rotate";

    // Revocable "request to join" link token (null = no active link). Opening the link
    // creates a PENDING membership the owner must approve; it never auto-joins.
    @Column(name = "join_token")
    private String joinToken;

    protected Space() {
        // for JPA
    }

    public Space(String name, String kind, UUID createdBy) {
        this.name = name;
        this.kind = kind;
        this.createdBy = createdBy;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getKind() { return kind; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getDriveSyncMode() { return driveSyncMode; }
    public void setDriveSyncMode(String driveSyncMode) { this.driveSyncMode = driveSyncMode; }
    public String getJoinToken() { return joinToken; }
    public void setJoinToken(String joinToken) { this.joinToken = joinToken; }
}
