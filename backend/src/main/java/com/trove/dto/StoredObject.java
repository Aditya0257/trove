/*
 * ============================================================================
 *  StoredObject — result of storing a file (value object)
 * ============================================================================
 *  Purpose:        what StorageService.store(...) returns: the keys + basic facts
 *                  about the object it just wrote.
 *  Business use:    the caller (DocumentService) records these on the document row,
 *                  which is the index back into the durable object store.
 *  Design:         matches DESIGN.md §6.1 record StoredObject exactly.
 * ============================================================================
 */
package com.trove.dto;

public record StoredObject(
        String storageKey,
        String sidecarKey,
        String fileHash,
        long sizeBytes,
        String mimeType
) {
}
