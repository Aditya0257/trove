/*
 * ============================================================================
 *  DriveConnectionRepository — data access for linked Google Drives in a space
 * ============================================================================
 */
package com.trove.repository;

import com.trove.entity.DriveConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriveConnectionRepository extends JpaRepository<DriveConnection, UUID> {
    /** All Drives linked to a space, oldest first (stable display + rotation order). */
    List<DriveConnection> findBySpaceIdOrderByConnectedAtAsc(UUID spaceId);

    /** Same Google account reconnecting → update in place rather than duplicate. */
    Optional<DriveConnection> findBySpaceIdAndGoogleEmail(UUID spaceId, String googleEmail);

    long countBySpaceId(UUID spaceId);

    /** Distinct spaces with at least one connected Drive (drives the scheduled sweep). */
    @Query("select distinct c.spaceId from DriveConnection c")
    List<UUID> findDistinctSpaceIds();
}
