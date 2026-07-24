/*
 * ============================================================================
 *  ReminderEventListener — turns a confirmed due date into a reminder
 * ============================================================================
 *  Purpose:        after a document is confirmed, auto-create its reminders (a dated
 *                  due/renewal nudge, plus a recurring one if it looks like a subscription).
 *  Business use:    the user confirms a bill or policy once; the right reminder appears
 *                  automatically — no extra step.
 *  Design:         @TransactionalEventListener(AFTER_COMMIT) so the reminder is only
 *                  created once the confirm has durably committed. Keeps documents
 *                  and reminders decoupled (documents just publish an event).
 * ============================================================================
 */
package com.trove.reminder;

import com.trove.document.DocumentConfirmedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ReminderEventListener {

    private final ReminderService reminderService;

    public ReminderEventListener(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentConfirmed(DocumentConfirmedEvent event) {
        reminderService.createRemindersFromDocument(
                event.spaceId(), event.documentId(), event.dueDate());
    }
}
