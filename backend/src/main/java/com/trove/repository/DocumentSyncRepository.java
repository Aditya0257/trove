/*
 * ============================================================================
 *  DocumentSyncRepository — per-document, per-connection Drive sync state
 * ============================================================================
 */
package com.trove.repository;

import com.trove.entity.DocumentSync;
import com.trove.entity.DocumentSyncId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentSyncRepository extends JpaRepository<DocumentSync, DocumentSyncId> {
    boolean existsByDocumentIdAndConnectionId(UUID documentId, UUID connectionId);

    List<DocumentSync> findByDocumentIdIn(List<UUID> documentIds);

    /** Bytes Trove has pushed to ONE Drive — sum of the sizes of docs synced to it. */
    @org.springframework.data.jpa.repository.Query(value = """
            select coalesce(sum(d.size_bytes), 0)
            from document d
            join document_sync s on s.document_id = d.id and s.connection_id = :connectionId
            """, nativeQuery = true)
    long troveBytesForConnection(@Param("connectionId") UUID connectionId);
}
