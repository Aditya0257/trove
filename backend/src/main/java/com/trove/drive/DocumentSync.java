/*
 * ============================================================================
 *  DocumentSync — records that a document was synced to an external target
 * ============================================================================
 *  Purpose:        maps `document_sync` (composite key document_id + target): the
 *                  external id (Drive file id) and when it synced.
 *  Business use:    makes sync idempotent — a document already synced is skipped.
 *  Design:         `target` is generic ('google_drive') so a future 2nd-cloud mirror
 *                  reuses the same table. @IdClass(DocumentSyncId).
 * ============================================================================
 */
package com.trove.drive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_sync")
@IdClass(DocumentSyncId.class)
public class DocumentSync {

    @Id
    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Id
    @Column(name = "target", nullable = false)
    private String target;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @CreationTimestamp
    @Column(name = "synced_at", nullable = false, updatable = false)
    private Instant syncedAt;

    protected DocumentSync() {
        // for JPA
    }

    public DocumentSync(UUID documentId, String target, String externalId) {
        this.documentId = documentId;
        this.target = target;
        this.externalId = externalId;
    }

    public UUID getDocumentId() { return documentId; }
    public String getTarget() { return target; }
    public String getExternalId() { return externalId; }
    public Instant getSyncedAt() { return syncedAt; }
}
