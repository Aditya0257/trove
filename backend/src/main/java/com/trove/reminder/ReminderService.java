/*
 * ============================================================================
 *  ReminderService — create, list, dismiss, and dispatch reminders
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Business logic for reminders: create them (manually or auto from a confirmed
 *  document's due date), list a space's reminders, dismiss one, and dispatch the
 *  ones that have come due.
 *
 *  Business use case
 *  -----------------
 *  Nudges the user before a bill is due / a policy renews / a warranty expires — a
 *  headline feature of the vault.
 *
 *  Solution architecture
 *  ---------------------
 *  Space-scoped and authorized via SpaceAuthorization. Auto-creation is triggered by
 *  a DocumentConfirmedEvent (after commit) so due dates are only turned into
 *  reminders once a human has verified them. dispatchDue() is called by the
 *  scheduler.
 *
 *  Reasoning & logic
 *  -----------------
 *  A due reminder fires leadDays BEFORE the due date (configurable). Auto-creation
 *  is idempotent per (document, type) so re-confirming won't duplicate reminders.
 *  Dispatch currently logs the notification and marks it sent — real channels
 *  (email/WhatsApp) are a later phase; the scheduling mechanism is what this slice
 *  delivers.
 * ============================================================================
 */
package com.trove.reminder;

import com.trove.common.error.NotFoundException;
import com.trove.space.SpaceAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ReminderRepository reminderRepository;
    private final SpaceAuthorization authorization;
    private final ReminderProperties props;

    public ReminderService(ReminderRepository reminderRepository, SpaceAuthorization authorization,
                           ReminderProperties props) {
        this.reminderRepository = reminderRepository;
        this.authorization = authorization;
        this.props = props;
    }

    /** Creates a manual reminder (owner/member only). */
    @Transactional
    public Reminder create(UUID spaceId, UUID userId, UUID documentId, String type, LocalDate remindOn) {
        authorization.requireCanWrite(spaceId, userId);
        if (!ReminderType.ALL.contains(type)) {
            throw new IllegalArgumentException("Invalid reminder type '" + type + "'. Use "
                    + ReminderType.ALL);
        }
        if (remindOn == null) {
            throw new IllegalArgumentException("remindOn (date) is required");
        }
        return reminderRepository.save(new Reminder(spaceId, documentId, type, remindOn));
    }

    /**
     * Auto-creates a 'due' reminder for a confirmed document's due date. Idempotent
     * per document; no-op when there is no due date. Called after confirm (the space
     * write was already authorized there), so it does not re-check permissions.
     *
     * REQUIRES_NEW: this runs from an AFTER_COMMIT event listener where the original
     * transaction has already committed. A plain @Transactional here would flush but
     * never commit (the row silently vanishes); a fresh transaction commits properly.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createDueReminderFromDocument(UUID spaceId, UUID documentId, LocalDate dueDate) {
        if (dueDate == null || documentId == null) {
            return;
        }
        if (reminderRepository.existsByDocumentIdAndType(documentId, ReminderType.DUE)) {
            return;
        }
        LocalDate remindOn = dueDate.minusDays(props.getLeadDays());
        reminderRepository.save(new Reminder(spaceId, documentId, ReminderType.DUE, remindOn));
        log.info("Auto-created 'due' reminder for document {} (due {}, remind {})",
                documentId, dueDate, remindOn);
    }

    /** Lists reminders in a space (optionally by status). Any member may read. */
    @Transactional(readOnly = true)
    public List<Reminder> list(UUID spaceId, UUID userId, String status) {
        authorization.requireCanRead(spaceId, userId);
        return (status == null || status.isBlank())
                ? reminderRepository.findBySpaceIdOrderByRemindOnAsc(spaceId)
                : reminderRepository.findBySpaceIdAndStatusOrderByRemindOnAsc(spaceId, status);
    }

    /** Dismisses a reminder (owner/member only). */
    @Transactional
    public Reminder dismiss(UUID reminderId, UUID userId) {
        Reminder reminder = reminderRepository.findById(reminderId)
                .orElseThrow(() -> new NotFoundException("Reminder not found: " + reminderId));
        authorization.requireCanWrite(reminder.getSpaceId(), userId);
        reminder.setStatus(ReminderStatus.DISMISSED);
        return reminderRepository.save(reminder);
    }

    /**
     * Dispatches every pending reminder whose date has arrived (across all spaces):
     * "sends" the notification (logged for now) and marks it sent. Returns the count.
     */
    @Transactional
    public int dispatchDue(LocalDate today) {
        List<Reminder> due = reminderRepository.findByStatusAndRemindOnLessThanEqual(
                ReminderStatus.PENDING, today);
        for (Reminder r : due) {
            // Notification channel (email/WhatsApp) is a later phase — log for now.
            log.info("REMINDER DUE — type={} space={} document={} remindOn={}",
                    r.getType(), r.getSpaceId(), r.getDocumentId(), r.getRemindOn());
            r.setStatus(ReminderStatus.SENT);
            reminderRepository.save(r);
        }
        if (!due.isEmpty()) {
            log.info("Dispatched {} due reminder(s)", due.size());
        }
        return due.size();
    }
}
