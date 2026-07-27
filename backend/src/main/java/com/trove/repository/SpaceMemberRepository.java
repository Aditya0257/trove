/*
 * ============================================================================
 *  SpaceMemberRepository — data access for memberships (the ACL)
 * ============================================================================
 *  Purpose:        look up a user's role in a space and list a space's members.
 * ============================================================================
 */
package com.trove.repository;
import com.trove.entity.SpaceMember;
import com.trove.entity.SpaceMemberId;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpaceMemberRepository extends JpaRepository<SpaceMember, SpaceMemberId> {

    Optional<SpaceMember> findBySpaceIdAndUserId(UUID spaceId, UUID userId);

    boolean existsBySpaceIdAndUserId(UUID spaceId, UUID userId);

    List<SpaceMember> findBySpaceId(UUID spaceId);

    /** A user's memberships in a given lifecycle state (e.g. their pending invitations). */
    List<SpaceMember> findByUserIdAndStatus(UUID userId, String status);
}
