/*
 * ============================================================================
 *  DocumentSync — records that a document was synced to a specific Drive
 * ============================================================================
 *  Purpose:        maps `document_sync` (composite key document_id + connection_id):
 *                  the external id (Drive file id) and when it synced.
 *  Business use:    makes sync idempotent per Drive — a document already in THIS Drive
 *                  is skipped. Keyed by connection so mirror mode can store the same
 *                  document in several Drives (one row each).
 *  Design:         `target` remains a plain descriptor column ('google_drive') for
 *                  readability/extensibility; the identity is (document_id, connection_id).
 * ============================================================================
 */
package com.trove.entity;

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
    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

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

    public DocumentSync(UUID documentId, UUID connectionId, String externalId) {
        this.documentId = documentId;
        this.connectionId = connectionId;
        this.target = "google_drive";
        this.externalId = externalId;
    }

    public UUID getDocumentId() { return documentId; }
    public UUID getConnectionId() { return connectionId; }
    public String getTarget() { return target; }
    public String getExternalId() { return externalId; }
    public Instant getSyncedAt() { return syncedAt; }
}
