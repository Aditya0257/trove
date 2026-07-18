/*
 * ============================================================================
 *  DocumentConfirmedEvent — "a document was confirmed" signal
 * ============================================================================
 *  Purpose:        published after a document's review is confirmed, carrying its
 *                  id, space, and (human-verified) due date.
 *  Business use:    lets the reminder feature auto-create a due reminder ONLY from a
 *                  confirmed (trusted) due date — without coupling documents to
 *                  reminders directly.
 *  Design:         consumed by an AFTER_COMMIT listener in the reminder feature.
 * ============================================================================
 */
package com.trove.document;

import java.time.LocalDate;
import java.util.UUID;

public record DocumentConfirmedEvent(UUID documentId, UUID spaceId, LocalDate dueDate) {
}
