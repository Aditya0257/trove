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
}
