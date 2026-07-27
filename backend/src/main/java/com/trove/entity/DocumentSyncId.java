/*
 * ============================================================================
 *  DocumentSyncId — composite key (document_id, connection_id) for document_sync
 * ============================================================================
 */
package com.trove.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class DocumentSyncId implements Serializable {

    private UUID documentId;
    private UUID connectionId;

    public DocumentSyncId() {
    }

    public DocumentSyncId(UUID documentId, UUID connectionId) {
        this.documentId = documentId;
        this.connectionId = connectionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentSyncId that)) return false;
        return Objects.equals(documentId, that.documentId) && Objects.equals(connectionId, that.connectionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, connectionId);
    }
}
