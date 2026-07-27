/*
 * ============================================================================
 *  DocumentRestoredEvent — "a trashed document was restored" signal
 * ============================================================================
 *  Purpose:        published after a document is restored from the trash.
 *  Business use:    lets the Drive feature move the file back out of Trove/_Deleted/
 *                  into its normal category/month folder in each synced Drive.
 *  Design:         consumed by an AFTER_COMMIT listener in the drive feature.
 * ============================================================================
 */
package com.trove.event;

import java.util.UUID;

public record DocumentRestoredEvent(UUID documentId, UUID spaceId) {
}
