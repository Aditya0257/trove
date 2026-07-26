/*
 * ============================================================================
 *  SpaceRepository — data access for spaces
 * ============================================================================
 *  Purpose:        persist and load spaces, including all spaces a user belongs to.
 * ============================================================================
 */
package com.trove.repository;
import com.trove.entity.Space;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpaceRepository extends JpaRepository<Space, UUID> {

    /** Resolve a space from its active join-link token. */
    Optional<Space> findByJoinToken(String joinToken);

    /** All spaces the given user is a member of, via space_member. */
    @Query("""
           select s from Space s
           where s.id in (
             select m.spaceId from SpaceMember m where m.userId = :userId and m.status = 'active'
           )
           order by s.createdAt
           """)
    List<Space> findAllForUser(UUID userId);
}
