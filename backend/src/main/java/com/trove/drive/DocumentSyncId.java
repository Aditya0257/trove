/*
 * ============================================================================
 *  DocumentSyncId — composite key (document_id, target) for document_sync
 * ============================================================================
 */
package com.trove.drive;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class DocumentSyncId implements Serializable {

    private UUID documentId;
    private String target;

    public DocumentSyncId() {
    }

    public DocumentSyncId(UUID documentId, String target) {
        this.documentId = documentId;
        this.target = target;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentSyncId that)) return false;
        return Objects.equals(documentId, that.documentId) && Objects.equals(target, that.target);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, target);
    }
}
