/*
 * ============================================================================
 *  EmailSender — swappable outbound-email seam
 * ============================================================================
 *  Purpose:        one interface for sending a plain-text email, so the provider
 *                  (Brevo today, anything tomorrow) is never welded into callers.
 *  Business use:    reminder notifications are delivered through this; the same seam
 *                  can later carry export links or invites.
 *  Design:         mirrors the StorageService / ExtractionProvider philosophy —
 *                  code talks to the interface; config picks the implementation.
 * ============================================================================
 */
package com.trove.notification;

import java.util.List;

public interface EmailSender {

    /**
     * Sends a plain-text email to each recipient. Implementations must be safe to
     * call when email is not configured (no-op + log) and must never throw for a
     * delivery failure — reminders should degrade, not crash the scheduler.
     *
     * @return true if the provider accepted the message, false if skipped/failed.
     */
    boolean send(List<String> to, String subject, String textBody);
}
