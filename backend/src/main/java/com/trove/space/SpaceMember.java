/*
 * ============================================================================
 *  SpaceMember — a user's membership + role in a space
 * ============================================================================
 *  Purpose:        maps the `space_member` join table (DESIGN.md §2) with a
 *                  composite (space_id, user_id) key and a role.
 *  Business use:    this table IS the access-control list — who can see/modify a
 *                  space's documents, and at what role.
 *  Design:         @IdClass(SpaceMemberId). joined_at managed by Hibernate.
 * ============================================================================
 */
package com.trove.space;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "space_member")
@IdClass(SpaceMemberId.class)
public class SpaceMember {

    @Id
    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role", nullable = false)
    private String role;

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected SpaceMember() {
        // for JPA
    }

    public SpaceMember(UUID spaceId, UUID userId, String role) {
        this.spaceId = spaceId;
        this.userId = userId;
        this.role = role;
    }

    public UUID getSpaceId() { return spaceId; }
    public UUID getUserId() { return userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Instant getJoinedAt() { return joinedAt; }
}
