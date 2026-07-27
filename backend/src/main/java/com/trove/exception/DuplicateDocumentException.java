/*
 * ============================================================================
 *  DuplicateDocumentException — same file already exists in this space
 * ============================================================================
 *  Purpose:        signal that an upload's content hash already exists in the
 *                  target space, and carry the existing document's id back to the
 *                  caller so the client can point the user at it.
 *  Business use:    people re-forward the same bill; we reject the duplicate rather
 *                  than store the same bytes twice (brief: duplicate detection).
 *  Design:         mapped to HTTP 409 Conflict by ApiExceptionHandler; the existing
 *                  id is surfaced in the error payload.
 * ============================================================================
 */
package com.trove.exception;

import java.util.UUID;

public class DuplicateDocumentException extends RuntimeException {

    private final UUID existingDocumentId;

    public DuplicateDocumentException(UUID existingDocumentId) {
        super("A document with identical content already exists in this space: " + existingDocumentId);
        this.existingDocumentId = existingDocumentId;
    }

    public UUID getExistingDocumentId() {
        return existingDocumentId;
    }
}
