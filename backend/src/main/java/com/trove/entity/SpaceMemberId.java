/*
 * ============================================================================
 *  SpaceMemberId — composite primary key for space_member
 * ============================================================================
 *  Purpose:        the (space_id, user_id) composite key identifying a membership.
 *  Design:         @IdClass for the SpaceMember entity; Serializable with
 *                  equals/hashCode as JPA requires.
 * ============================================================================
 */
package com.trove.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class SpaceMemberId implements Serializable {

    private UUID spaceId;
    private UUID userId;

    public SpaceMemberId() {
    }

    public SpaceMemberId(UUID spaceId, UUID userId) {
        this.spaceId = spaceId;
        this.userId = userId;
    }

    public UUID getSpaceId() { return spaceId; }
    public UUID getUserId() { return userId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SpaceMemberId that)) return false;
        return Objects.equals(spaceId, that.spaceId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(spaceId, userId);
    }
}
