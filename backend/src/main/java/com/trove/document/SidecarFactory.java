/*
 * ============================================================================
 *  SidecarFactory — builds a DocumentSidecar snapshot from a Document row
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Converts the current state of a Document (plus its resolved category code and
 *  merchant name) into the immutable DocumentSidecar written to object storage.
 *
 *  Business use case
 *  -----------------
 *  The sidecar is the disaster-recovery payload — the thing we rebuild the DB from.
 *  Centralizing its construction guarantees every write (upload, post-extraction,
 *  post-confirm) produces a consistent, complete snapshot.
 *
 *  Solution architecture
 *  ---------------------
 *  Used by DocumentService and ExtractionWorker. Category/merchant are passed in as
 *  resolved display values because the Document row only stores their UUIDs.
 *
 *  Reasoning & logic
 *  -----------------
 *  fileHash is emitted in the "sha256:<hex>" form from DESIGN.md §6.1 (the DB stores
 *  the bare hex for dedupe; the sidecar is the human/LLM-readable copy).
 * ============================================================================
 */
package com.trove.document;

import com.trove.storage.DocumentSidecar;

public final class SidecarFactory {

    private SidecarFactory() {
    }

    /** Builds a sidecar snapshot from the document's current fields. */
    public static DocumentSidecar of(Document doc, String categoryCode, String merchantName) {
        String hash = doc.getFileHash();
        String prefixedHash = hash != null && hash.startsWith("sha256:") ? hash : "sha256:" + hash;
        return new DocumentSidecar(
                doc.getId(),
                doc.getSpaceId(),
                doc.getUploadedBy(),
                doc.getStorageKey(),
                prefixedHash,
                categoryCode,
                merchantName,
                doc.getDocDate(),
                doc.getAmount(),
                doc.getCurrency(),
                doc.getDueDate(),
                doc.getStatus(),
                doc.getRawText(),
                doc.getExtra(),
                doc.getCreatedAt()
        );
    }
}
