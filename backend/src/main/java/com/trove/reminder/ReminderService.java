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

import com.trove.category.Category;
import com.trove.category.CategoryRepository;
import com.trove.common.error.NotFoundException;
import com.trove.document.Document;
import com.trove.document.DocumentRepository;
import com.trove.document.DocumentStatus;
import com.trove.merchant.Merchant;
import com.trove.merchant.MerchantRepository;
import com.trove.space.SpaceAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    /** Categories whose "due date" is really a RENEWAL date (a policy/plan lapsing), not a bill. */
    private static final List<String> RENEWAL_CATEGORIES = List.of("insurance", "subscription");
    /** Fewest same-merchant documents before a regular cadence is trustworthy as a subscription. */
    private static final int SUBSCRIPTION_MIN_DOCS = 3;

    private final ReminderRepository reminderRepository;
    private final SpaceAuthorization authorization;
    private final ReminderProperties props;
    private final ReminderNotifier notifier;
    private final DocumentRepository documentRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;

    public ReminderService(ReminderRepository reminderRepository, SpaceAuthorization authorization,
                           ReminderProperties props, ReminderNotifier notifier,
                           DocumentRepository documentRepository, CategoryRepository categoryRepository,
                           MerchantRepository merchantRepository) {
        this.reminderRepository = reminderRepository;
        this.authorization = authorization;
        this.props = props;
        this.notifier = notifier;
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
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
     * Auto-creates reminders for a freshly confirmed document. Two independent parts:
     *
     *  1) A dated reminder from the confirmed due/expiry date, at each configured lead
     *     time (default 7/1/0 days before). Its TYPE follows the category: a policy or
     *     subscription "due date" is really a RENEWAL; everything else is a payment DUE.
     *  2) Subscription detection: if the same merchant has been billing on a regular
     *     cadence (monthly / quarterly / yearly across several documents), schedule a
     *     recurring RENEWAL reminder for the next expected date.
     *
     * Idempotent (each dated lead is guarded; the recurring one is guarded per merchant),
     * so re-confirming never duplicates. No-op safely when data is missing. Called after
     * confirm (space write already authorized there), so it does not re-check permissions.
     *
     * REQUIRES_NEW: this runs from an AFTER_COMMIT event listener where the original
     * transaction has already committed. A plain @Transactional here would flush but
     * never commit (the row silently vanishes); a fresh transaction commits properly.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createRemindersFromDocument(UUID spaceId, UUID documentId, LocalDate dueDate) {
        if (documentId == null) {
            return;
        }
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            return;
        }
        String categoryCode = doc.getCategoryId() == null ? null
                : categoryRepository.findById(doc.getCategoryId()).map(Category::getCode).orElse(null);

        boolean madeDated = false;
        if (dueDate != null) {
            String type = reminderTypeFor(categoryCode);
            boolean made = false;
            for (int lead : props.getLeadDaysList()) {
                LocalDate remindOn = dueDate.minusDays(lead);
                if (!reminderRepository.existsByDocumentIdAndTypeAndRemindOn(documentId, type, remindOn)) {
                    reminderRepository.save(new Reminder(spaceId, documentId, type, remindOn));
                    made = true;
                }
            }
            if (made) {
                madeDated = true;
                log.info("Auto-created {} reminder(s) for document {} (date {}, leads {})",
                        type, documentId, dueDate, props.getLeadDaysList());
            }
        }

        // A purchase's warranty end date (set on the review screen, stored in `extra`)
        // becomes a warranty-expiry reminder a couple of weeks ahead, so you can still
        // claim or arrange a repair before cover lapses.
        LocalDate warrantyUntil = warrantyDateOf(doc);
        if (warrantyUntil != null) {
            String title = merchantTitle(doc, "warranty");
            boolean made = false;
            for (int lead : props.getWarrantyLeadDaysList()) {
                LocalDate remindOn = warrantyUntil.minusDays(lead);
                if (!reminderRepository.existsByDocumentIdAndTypeAndRemindOn(
                        documentId, ReminderType.WARRANTY_EXPIRY, remindOn)) {
                    Reminder r = new Reminder(spaceId, documentId, ReminderType.WARRANTY_EXPIRY, remindOn);
                    r.setTitle(title);
                    reminderRepository.save(r);
                    made = true;
                }
            }
            if (made) {
                madeDated = true;
                log.info("Auto-created warranty reminder(s) for document {} (expires {}, leads {})",
                        documentId, warrantyUntil, props.getWarrantyLeadDaysList());
            }
        }

        // Only look for a subscription cadence when this document didn't already yield a
        // dated reminder, so a policy/warranty with an explicit date isn't double-flagged.
        if (!madeDated) {
            detectSubscription(spaceId, doc);
        }
    }

    /** The warranty end date a user set on review, stored as an ISO string in extra; null if none/unparseable. */
    private LocalDate warrantyDateOf(Document doc) {
        Object v = doc.getExtra() == null ? null : doc.getExtra().get("warrantyUntil");
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.toString().trim());
        } catch (Exception e) {
            log.warn("Ignoring unparseable warrantyUntil '{}' on document {}", v, doc.getId());
            return null;
        }
    }

    /** "<Merchant> <suffix>" when a merchant is known, else just the suffix (e.g. "warranty"). */
    private String merchantTitle(Document doc, String suffix) {
        String merchant = doc.getMerchantId() == null ? null
                : merchantRepository.findById(doc.getMerchantId()).map(Merchant::getCanonicalName).orElse(null);
        return merchant != null ? merchant + " " + suffix : Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
    }

    /** A policy/subscription date is a renewal; everything else is a payment due. */
    private String reminderTypeFor(String categoryCode) {
        return categoryCode != null && RENEWAL_CATEGORIES.contains(categoryCode)
                ? ReminderType.RENEWAL : ReminderType.DUE;
    }

    /**
     * Detects a regular billing cadence for the document's merchant and, if found, schedules
     * a recurring RENEWAL reminder for the next expected date. Guards against duplicates: it
     * skips when an active recurring renewal already exists for any of that merchant's docs.
     */
    private void detectSubscription(UUID spaceId, Document doc) {
        if (doc.getMerchantId() == null || doc.getDocDate() == null) {
            return;
        }
        List<Document> history = documentRepository
                .findBySpaceIdAndMerchantIdAndStatusOrderByDocDateAsc(
                        spaceId, doc.getMerchantId(), DocumentStatus.CONFIRMED)
                .stream().filter(d -> d.getDocDate() != null).toList();
        if (history.size() < SUBSCRIPTION_MIN_DOCS) {
            return;
        }
        String cadence = inferCadence(history.stream().map(Document::getDocDate).toList());
        if (cadence == null) {
            return;
        }
        // Already tracking this merchant's subscription? (active recurring renewal on any of its docs)
        var merchantDocIds = history.stream().map(Document::getId).toList();
        boolean alreadyTracked = reminderRepository.findBySpaceIdOrderByRemindOnAsc(spaceId).stream()
                .anyMatch(r -> ReminderType.RENEWAL.equals(r.getType())
                        && ReminderRecurrence.repeats(r.getRecurrence())
                        && (ReminderStatus.PENDING.equals(r.getStatus()) || ReminderStatus.SENT.equals(r.getStatus()))
                        && merchantDocIds.contains(r.getDocumentId()));
        if (alreadyTracked) {
            return;
        }
        LocalDate last = history.get(history.size() - 1).getDocDate();
        LocalDate next = ReminderRecurrence.next(cadence, last, LocalDate.now());
        if (next == null) {
            return;
        }
        String merchant = merchantRepository.findById(doc.getMerchantId())
                .map(Merchant::getCanonicalName).orElse("Subscription");
        Reminder r = new Reminder(spaceId, doc.getId(), ReminderType.RENEWAL, next);
        r.setRecurrence(cadence);
        r.setTitle(merchant + " renewal");
        reminderRepository.save(r);
        log.info("Detected {} subscription for merchant {} in space {}; scheduled renewal on {}",
                cadence, doc.getMerchantId(), spaceId, next);
    }

    /**
     * Infers a billing cadence from a series of dates (oldest first). Returns weekly / monthly /
     * quarterly / yearly when the gaps between consecutive dates consistently fall in that band,
     * else null. Requires every gap to sit inside the chosen band, so an irregular history (a
     * shop visited whenever) is not mistaken for a subscription.
     */
    private String inferCadence(List<LocalDate> dates) {
        if (dates.size() < 2) {
            return null;
        }
        long[] gaps = new long[dates.size() - 1];
        long sum = 0;
        for (int i = 1; i < dates.size(); i++) {
            gaps[i - 1] = ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i));
            sum += gaps[i - 1];
        }
        double avg = (double) sum / gaps.length;
        String band = bandFor(avg);
        if (band == null) {
            return null;
        }
        for (long g : gaps) {
            if (!band.equals(bandFor(g))) {
                return null; // an off-cadence gap: not a clean subscription
            }
        }
        return band;
    }

    /** Maps a day-count to a cadence band, with tolerance for month-length drift; null if none fits. */
    private String bandFor(double days) {
        if (days >= 5 && days <= 9) {
            return ReminderRecurrence.WEEKLY;
        }
        if (days >= 26 && days <= 35) {
            return ReminderRecurrence.MONTHLY;
        }
        if (days >= 82 && days <= 98) {
            return ReminderRecurrence.QUARTERLY;
        }
        if (days >= 350 && days <= 380) {
            return ReminderRecurrence.YEARLY;
        }
        return null;
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
