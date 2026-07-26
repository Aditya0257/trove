/*
 * ============================================================================
 *  DocumentUploadedEvent — "a document row was committed" signal
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  A tiny domain event published when an upload's document row is saved, carrying
 *  just the document id.
 *
 *  Business use case
 *  -----------------
 *  Decouples "store the upload" from "extract it". The upload returns fast; a
 *  listener kicks off async extraction only AFTER the row is durably committed.
 *
 *  Solution architecture
 *  ---------------------
 *  Consumed by ExtractionEventListener via @TransactionalEventListener(AFTER_COMMIT)
 *  — the key to the no-race, at-least-once async design (DECISIONS.md → D3).
 *
 *  Reasoning & logic
 *  -----------------
 *  Carries only the id (not the entity) so the listener re-reads a fresh, committed
 *  copy in its own transaction rather than sharing a possibly-stale instance.
 * ============================================================================
 */
package com.trove.event;

import java.util.UUID;

public record DocumentUploadedEvent(UUID documentId) {
}
