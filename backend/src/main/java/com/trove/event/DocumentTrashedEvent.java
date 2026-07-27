/*
 * ============================================================================
 *  DocumentTrashedEvent — "a document was soft-deleted" signal
 * ============================================================================
 *  Purpose:        published after a document is moved to the trash (status=deleted).
 *  Business use:    lets the Drive feature move the file into Trove/_Deleted/ in each
 *                  Drive it was synced to, without coupling documents to Drive.
 *  Design:         consumed by an AFTER_COMMIT listener in the drive feature.
 * ============================================================================
 */
package com.trove.event;

import java.util.UUID;

public record DocumentTrashedEvent(UUID documentId, UUID spaceId) {
}
