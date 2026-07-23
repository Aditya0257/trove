/*
 * ============================================================================
 *  Drive repositories — data access for the Drive integration tables
 * ============================================================================
 *  Purpose:        one file holding the three small Spring Data repositories used by
 *                  the Drive feature (connection, folder cache, per-doc sync state).
 * ============================================================================
 */
package com.trove.drive;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface DriveConnectionRepository extends JpaRepository<DriveConnection, UUID> {
    /** All Drives linked to a space, oldest first (stable display + rotation order). */
    List<DriveConnection> findBySpaceIdOrderByConnectedAtAsc(UUID spaceId);

    /** Same Google account reconnecting → update in place rather than duplicate. */
    Optional<DriveConnection> findBySpaceIdAndGoogleEmail(UUID spaceId, String googleEmail);

    long countBySpaceId(UUID spaceId);

    /** Distinct spaces with at least one connected Drive (drives the scheduled sweep). */
    @org.springframework.data.jpa.repository.Query("select distinct c.spaceId from DriveConnection c")
    List<UUID> findDistinctSpaceIds();
}

interface DriveFolderRepository extends JpaRepository<DriveFolder, UUID> {
    Optional<DriveFolder> findByConnectionIdAndPath(UUID connectionId, String path);
}

interface DocumentSyncRepository extends JpaRepository<DocumentSync, DocumentSyncId> {
    boolean existsByDocumentIdAndConnectionId(UUID documentId, UUID connectionId);

    List<DocumentSync> findByDocumentIdIn(List<UUID> documentIds);

    /** Bytes Trove has pushed to ONE Drive — sum of the sizes of docs synced to it. */
    @org.springframework.data.jpa.repository.Query(value = """
            select coalesce(sum(d.size_bytes), 0)
            from document d
            join document_sync s on s.document_id = d.id and s.connection_id = :connectionId
            """, nativeQuery = true)
    long troveBytesForConnection(@org.springframework.data.repository.query.Param("connectionId") UUID connectionId);
}
