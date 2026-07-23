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
    Optional<DriveConnection> findBySpaceId(UUID spaceId);
}

interface DriveFolderRepository extends JpaRepository<DriveFolder, UUID> {
    Optional<DriveFolder> findBySpaceIdAndPath(UUID spaceId, String path);
}

interface DocumentSyncRepository extends JpaRepository<DocumentSync, DocumentSyncId> {
    boolean existsByDocumentIdAndTarget(UUID documentId, String target);

    List<DocumentSync> findByDocumentIdIn(List<UUID> documentIds);

    /** Total bytes Trove has pushed to a target for a space — sum of synced docs' sizes. */
    @org.springframework.data.jpa.repository.Query(value = """
            select coalesce(sum(d.size_bytes), 0)
            from document d
            join document_sync s on s.document_id = d.id and s.target = :target
            where d.space_id = :spaceId
            """, nativeQuery = true)
    long troveBytesForSpace(@org.springframework.data.repository.query.Param("spaceId") UUID spaceId,
                            @org.springframework.data.repository.query.Param("target") String target);
}
