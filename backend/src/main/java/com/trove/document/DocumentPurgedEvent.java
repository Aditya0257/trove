/*
 * ============================================================================
 *  DocumentPurgedEvent — "a document is being purged for good" signal
 * ============================================================================
 *  Purpose:        published while purging a document, BEFORE its row (and the
 *                  cascading drive_sync rows) are removed.
 *  Business use:    lets the Drive feature delete the file from every Drive it lived
 *                  in while the sync rows are still readable.
 *  Design:         consumed by a SYNCHRONOUS @EventListener (not AFTER_COMMIT) so it
 *                  runs before the cascade deletes the drive_sync rows.
 * ============================================================================
 */
package com.trove.document;

import java.util.UUID;

public record DocumentPurgedEvent(UUID documentId, UUID spaceId) {
}
