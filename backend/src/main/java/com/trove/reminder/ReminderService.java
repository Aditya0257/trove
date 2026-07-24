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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ReminderRepository reminderRepository;
    private final SpaceAuthorization authorization;
    private final ReminderProperties props;
    private final ReminderNotifier notifier;

    public ReminderService(ReminderRepository reminderRepository, SpaceAuthorization authorization,
                           ReminderProperties props, ReminderNotifier notifier) {
        this.reminderRepository = reminderRepository;
        this.authorization = authorization;
        this.props = props;
        this.notifier = notifier;
    }

    /** Creates a manual reminder (owner/member only). */
    @Transactional
    public Reminder create(UUID spaceId, UUID userId, UUID documentId, String type, String title,
                           LocalDate remindOn, String recurrence) {
        authorization.requireCanWrite(spaceId, userId);
        validateType(type);
        validateRecurrence(recurrence);
        if (remindOn == null) {
            throw new IllegalArgumentException("remindOn (date) is required");
        }
        Reminder r = new Reminder(spaceId, documentId, type, remindOn);
        r.setTitle(trimToNull(title));
        r.setRecurrence(normalizeRecurrence(recurrence));
        return reminderRepository.save(r);
    }

    /**
     * Snoozes a reminder to fire {@code days} from today and puts it back to pending
     * (so a sent/overdue one nudges again later). days=0 means "today" - used to
     * reopen a done/dismissed reminder as due now.
     */
    @Transactional
    public Reminder snooze(UUID reminderId, UUID userId, int days) {
        Reminder r = findWritable(reminderId, userId);
        r.setRemindOn(LocalDate.now().plusDays(Math.max(0, days)));
        r.setStatus(ReminderStatus.PENDING);
        r.setCompletedAt(null);
        return reminderRepository.save(r);
    }

    /**
     * Marks a reminder handled. For a recurring reminder this also schedules the next
     * occurrence (at the recurrence interval, jumping to the next future date), so the
     * series continues without the user re-creating it. Rollover happens here - not at
     * dispatch - so a reminder is only ever advanced once, when the user says it's done.
     */
    @Transactional
    public Reminder markDone(UUID reminderId, UUID userId) {
        Reminder r = findWritable(reminderId, userId);
        if (ReminderRecurrence.repeats(r.getRecurrence())) {
            LocalDate nextDate = ReminderRecurrence.next(r.getRecurrence(), r.getRemindOn(), LocalDate.now());
            if (nextDate != null) {
                Reminder next = new Reminder(r.getSpaceId(), r.getDocumentId(), r.getType(), nextDate);
                next.setTitle(r.getTitle());
                next.setRecurrence(r.getRecurrence());
                reminderRepository.save(next);
                log.info("Recurring reminder {} done; scheduled next {} on {}", r.getId(), r.getRecurrence(), nextDate);
            }
        }
        r.setStatus(ReminderStatus.DONE);
        r.setCompletedAt(Instant.now());
        return reminderRepository.save(r);
    }

    /** Edits a reminder's fields (owner/member only). The edit form sends the full set. */
    @Transactional
    public Reminder update(UUID reminderId, UUID userId, String type, String title,
                           LocalDate remindOn, String recurrence, UUID documentId) {
        Reminder r = findWritable(reminderId, userId);
        validateType(type);
        validateRecurrence(recurrence);
        if (remindOn == null) {
            throw new IllegalArgumentException("remindOn (date) is required");
        }
        r.setType(type);
        r.setTitle(trimToNull(title));
        r.setRemindOn(remindOn);
        r.setRecurrence(normalizeRecurrence(recurrence));
        r.setDocumentId(documentId);
        return reminderRepository.save(r);
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
        // One reminder per configured lead time (default 7/1/0 days before), so a
        // warranty/bill/renewal gets an early heads-up and a last nudge. Each lead is
        // guarded independently, so re-confirming a document never duplicates a date.
        for (int lead : props.getLeadDaysList()) {
            LocalDate remindOn = dueDate.minusDays(lead);
            if (reminderRepository.existsByDocumentIdAndTypeAndRemindOn(
                    documentId, ReminderType.DUE, remindOn)) {
                continue;
            }
            reminderRepository.save(new Reminder(spaceId, documentId, ReminderType.DUE, remindOn));
        }
        log.info("Auto-created due reminders for document {} (due {}, leads {})",
                documentId, dueDate, props.getLeadDaysList());
    }

    /** Lists reminders in a space (optionally by status). Any member may read. */
    @Transactional(readOnly = true)
    public List<Reminder> list(UUID spaceId, UUID userId, String status) {
        authorization.requireCanRead(spaceId, userId);
        return (status == null || status.isBlank())
                ? reminderRepository.findBySpaceIdOrderByRemindOnAsc(spaceId)
                : reminderRepository.findBySpaceIdAndStatusOrderByRemindOnAsc(spaceId, status);
    }

    /** Dismisses a reminder - "never mind" (owner/member only). */
    @Transactional
    public Reminder dismiss(UUID reminderId, UUID userId) {
        Reminder reminder = findWritable(reminderId, userId);
        reminder.setStatus(ReminderStatus.DISMISSED);
        return reminderRepository.save(reminder);
    }

    /** Loads a reminder and checks the caller may write its space, else 404/403. */
    private Reminder findWritable(UUID reminderId, UUID userId) {
        Reminder reminder = reminderRepository.findById(reminderId)
                .orElseThrow(() -> new NotFoundException("Reminder not found: " + reminderId));
        authorization.requireCanWrite(reminder.getSpaceId(), userId);
        return reminder;
    }

    private void validateType(String type) {
        if (!ReminderType.ALL.contains(type)) {
            throw new IllegalArgumentException("Invalid reminder type '" + type + "'. Use " + ReminderType.ALL);
        }
    }

    private void validateRecurrence(String recurrence) {
        if (recurrence != null && !recurrence.isBlank() && !ReminderRecurrence.ALL.contains(recurrence)) {
            throw new IllegalArgumentException("Invalid recurrence '" + recurrence + "'. Use " + ReminderRecurrence.ALL);
        }
    }

    /** Blank/absent recurrence means a one-off reminder. */
    private String normalizeRecurrence(String recurrence) {
        return recurrence == null || recurrence.isBlank() ? ReminderRecurrence.NONE : recurrence;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
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
            // Deliver via the notifier (emails the space's members). A delivery failure
            // must not stop the sweep or wedge the reminder, so we still mark it sent.
            try {
                notifier.dispatch(r, today);
            } catch (Exception e) {
                log.warn("Reminder {} dispatch failed: {}", r.getId(), e.getMessage());
            }
            r.setStatus(ReminderStatus.SENT);
            reminderRepository.save(r);
        }
        if (!due.isEmpty()) {
            log.info("Dispatched {} due reminder(s)", due.size());
        }
        return due.size();
    }
}
