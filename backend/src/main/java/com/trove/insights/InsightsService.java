/*
 * ============================================================================
 *  InsightsService — document intelligence over confirmed documents
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Read-only "document intelligence" for a space, computed from confirmed documents
 *  only (verified dates and amounts):
 *    - expiring soon: one unified list of bills due, warranties ending and policy/ID
 *      renewals coming up (or recently past), soonest first.
 *    - recurring: merchant+category groups that repeat on a regular cadence
 *      (weekly/monthly/quarterly/yearly) — the subscription/recurring view — with the
 *      predicted next occurrence.
 *
 *  Business use case
 *  -----------------
 *  "What do I need to act on soon?" and "what am I paying for on repeat?" — the most
 *  immediately useful day-to-day value, with zero extra AI cost (pure DB + arithmetic).
 *
 *  Solution architecture
 *  ---------------------
 *  Mirrors AnalyticsService: authorize the caller (any member may read), load the
 *  space's confirmed documents, and fold them in memory (the per-space document count
 *  is small). Cadence detection reuses the same tolerance bands as the reminder
 *  subscription detector, and ReminderRecurrence.next() predicts the next date.
 * ============================================================================
 */
package com.trove.insights;

import com.trove.category.Category;
import com.trove.category.CategoryRepository;
import com.trove.document.Document;
import com.trove.document.DocumentRepository;
import com.trove.document.DocumentStatus;
import com.trove.merchant.Merchant;
import com.trove.merchant.MerchantRepository;
import com.trove.reminder.Reminder;
import com.trove.reminder.ReminderRecurrence;
import com.trove.reminder.ReminderRepository;
import com.trove.reminder.ReminderStatus;
import com.trove.reminder.ReminderType;
import com.trove.space.SpaceAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class InsightsService {

    /** Categories whose upcoming date is a renewal (a policy/subscription), not a bill due. */
    private static final Set<String> RENEWAL_CATEGORIES = Set.of("insurance", "subscription");
    /** Emails are filed under Mail, never counted here (matches spend/list). */
    private static final String EMAIL_CATEGORY = "email";
    /** A merchant+category must have at least this many confirmed docs to read as recurring. */
    private static final int RECURRING_MIN_DOCS = 2;
    /** How far back an already-passed expiry still shows (so a just-expired ID isn't hidden). */
    private static final int OVERDUE_GRACE_DAYS = 30;

    private final DocumentRepository documentRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;
    private final ReminderRepository reminderRepository;
    private final SpaceAuthorization authorization;

    public InsightsService(DocumentRepository documentRepository, CategoryRepository categoryRepository,
                           MerchantRepository merchantRepository, ReminderRepository reminderRepository,
                           SpaceAuthorization authorization) {
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
        this.reminderRepository = reminderRepository;
        this.authorization = authorization;
    }

    /**
     * A unified "expiring soon" list: bills due, warranties ending and renewals within the
     * next {@code withinDays}, plus anything that lapsed in the last {@link #OVERDUE_GRACE_DAYS}
     * days. Soonest first; a negative daysLeft means it is already overdue.
     */
    @Transactional(readOnly = true)
    public List<ExpiringItem> expiring(UUID spaceId, UUID userId, int withinDays) {
        authorization.requireCanRead(spaceId, userId);
        final LocalDate today = LocalDate.now();
        final LocalDate horizon = today.plusDays(Math.max(1, withinDays));
        final LocalDate earliest = today.minusDays(OVERDUE_GRACE_DAYS);

        final Map<UUID, Category> catCache = new HashMap<>();
        final Map<UUID, String> merchantCache = new HashMap<>();
        final List<ExpiringItem> items = new ArrayList<>();
        // Anything the user has already dealt with in Reminders (marked Done or Dismissed)
        // is no longer "coming up", so it drops off this overview. Reminders remains the
        // action inbox; Insights shows only what is still outstanding. Keyed as
        // "documentId|group" where group folds due/renewal together (both ride the due date).
        final Set<String> handled = handledKeys(spaceId);

        for (Document d : documentRepository.findBySpaceIdAndStatus(spaceId, DocumentStatus.CONFIRMED)) {
            Category cat = category(d.getCategoryId(), catCache);
            String code = cat != null ? cat.getCode() : null;
            if (EMAIL_CATEGORY.equals(code)) {
                continue;
            }
            String title = titleOf(d, cat, merchantCache);
            String docId = d.getId().toString();

            // Bill due / policy renewal / ID expiry — all ride the document's due date.
            LocalDate due = d.getDueDate();
            if (inWindow(due, earliest, horizon) && !handled.contains(docId + "|date")) {
                String kind = RENEWAL_CATEGORIES.contains(code) ? "renewal" : "due";
                items.add(item(d, title, code, kind, due, today));
            }

            // Warranty end lives in extra.warrantyUntil (ISO date), set at review time.
            LocalDate warranty = parseDate(d.getExtra() == null ? null : d.getExtra().get("warrantyUntil"));
            if (inWindow(warranty, earliest, horizon) && !handled.contains(docId + "|warranty")) {
                items.add(item(d, title, code, "warranty", warranty, today));
            }
        }

        items.sort((a, b) -> a.date().compareTo(b.date()));
        return items;
    }

    /**
     * The set of "documentId|group" keys the user has already resolved via Reminders, so
     * Insights can hide them. A reminder marked Done or Dismissed counts as handled; the
     * group folds a warranty-expiry reminder to "warranty" and due/renewal to "date" (they
     * both derive from the document's due date).
     */
    private Set<String> handledKeys(UUID spaceId) {
        Set<String> keys = new HashSet<>();
        for (Reminder r : reminderRepository.findBySpaceIdOrderByRemindOnAsc(spaceId)) {
            if (r.getDocumentId() == null) {
                continue;
            }
            boolean resolved = ReminderStatus.DONE.equals(r.getStatus())
                    || ReminderStatus.DISMISSED.equals(r.getStatus());
            if (!resolved) {
                continue;
            }
            String group = ReminderType.WARRANTY_EXPIRY.equals(r.getType()) ? "warranty" : "date";
            keys.add(r.getDocumentId() + "|" + group);
        }
        return keys;
    }

    /**
     * Recurring/subscription groups: confirmed documents grouped by merchant + category that
     * repeat on a regular cadence, with the average amount and the predicted next occurrence.
     */
    @Transactional(readOnly = true)
    public List<RecurringGroup> recurring(UUID spaceId, UUID userId) {
        authorization.requireCanRead(spaceId, userId);
        final Map<UUID, Category> catCache = new HashMap<>();
        final Map<UUID, String> merchantCache = new HashMap<>();

        // Group by (merchant, category); only documents with a real merchant + date qualify.
        final Map<GroupKey, List<Document>> groups = new HashMap<>();
        for (Document d : documentRepository.findBySpaceIdAndStatus(spaceId, DocumentStatus.CONFIRMED)) {
            if (d.getMerchantId() == null || d.getDocDate() == null) {
                continue;
            }
            Category cat = category(d.getCategoryId(), catCache);
            if (cat != null && EMAIL_CATEGORY.equals(cat.getCode())) {
                continue;
            }
            groups.computeIfAbsent(new GroupKey(d.getMerchantId(), d.getCategoryId()), k -> new ArrayList<>())
                    .add(d);
        }

        final LocalDate today = LocalDate.now();
        final List<RecurringGroup> out = new ArrayList<>();
        for (Map.Entry<GroupKey, List<Document>> e : groups.entrySet()) {
            List<Document> docs = e.getValue();
            if (docs.size() < RECURRING_MIN_DOCS) {
                continue;
            }
            docs.sort((x, y) -> x.getDocDate().compareTo(y.getDocDate()));
            List<LocalDate> dates = docs.stream().map(Document::getDocDate).toList();
            String cadence = cadenceOf(dates);
            if (cadence == null) {
                continue; // no regular rhythm -> not a subscription
            }
            LocalDate lastSeen = dates.get(dates.size() - 1);
            Category cat = category(e.getKey().categoryId(), catCache);
            out.add(new RecurringGroup(
                    merchantName(e.getKey().merchantId(), merchantCache),
                    cat != null ? cat.getCode() : null,
                    cat != null ? cat.getLabel() : null,
                    docs.size(),
                    cadence,
                    averageAmount(docs),
                    currencyOf(docs),
                    lastSeen,
                    ReminderRecurrence.next(cadence, lastSeen, today)));
        }
        // Soonest predicted renewal first; groups with no prediction fall to the end.
        out.sort((a, b) -> {
            if (a.nextExpected() == null && b.nextExpected() == null) {
                return 0;
            }
            if (a.nextExpected() == null) {
                return 1;
            }
            if (b.nextExpected() == null) {
                return -1;
            }
            return a.nextExpected().compareTo(b.nextExpected());
        });
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ExpiringItem item(Document d, String title, String code, String kind, LocalDate date,
                              LocalDate today) {
        return new ExpiringItem(d.getId().toString(), title, code, kind, date,
                ChronoUnit.DAYS.between(today, date), d.getAmount(), d.getCurrency());
    }

    private boolean inWindow(LocalDate d, LocalDate earliest, LocalDate horizon) {
        return d != null && !d.isBefore(earliest) && !d.isAfter(horizon);
    }

    private String titleOf(Document d, Category cat, Map<UUID, String> merchantCache) {
        String merchant = merchantName(d.getMerchantId(), merchantCache);
        if (merchant != null && !merchant.isBlank()) {
            return merchant;
        }
        if (cat != null && !"uncategorized".equals(cat.getCode())) {
            return cat.getLabel();
        }
        return d.getOriginalFilename() != null ? d.getOriginalFilename() : "Document";
    }

    private Category category(UUID categoryId, Map<UUID, Category> cache) {
        if (categoryId == null) {
            return null;
        }
        return cache.computeIfAbsent(categoryId,
                id -> categoryRepository.findById(id).orElse(null));
    }

    private String merchantName(UUID merchantId, Map<UUID, String> cache) {
        if (merchantId == null) {
            return null;
        }
        return cache.computeIfAbsent(merchantId,
                id -> merchantRepository.findById(id).map(Merchant::getCanonicalName).orElse(null));
    }

    private BigDecimal averageAmount(List<Document> docs) {
        List<BigDecimal> amounts = docs.stream().map(Document::getAmount).filter(a -> a != null).toList();
        if (amounts.isEmpty()) {
            return null;
        }
        BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(amounts.size()), 2, RoundingMode.HALF_UP);
    }

    private String currencyOf(List<Document> docs) {
        return docs.stream().map(Document::getCurrency).filter(c -> c != null && !c.isBlank())
                .findFirst().orElse("INR");
    }

    private static LocalDate parseDate(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The recurrence for a series of dates, or null if there is no regular rhythm. Uses the
     * same tolerance bands as the reminder subscription detector: the average gap must land in
     * a band AND every individual gap must land in that same band.
     */
    private static String cadenceOf(List<LocalDate> dates) {
        if (dates.size() < 2) {
            return null;
        }
        long[] gaps = new long[dates.size() - 1];
        long total = 0;
        for (int i = 1; i < dates.size(); i++) {
            gaps[i - 1] = ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i));
            total += gaps[i - 1];
        }
        String band = bandFor((double) total / gaps.length);
        if (band == null) {
            return null;
        }
        for (long gap : gaps) {
            if (!band.equals(bandFor(gap))) {
                return null;
            }
        }
        return band;
    }

    /** Maps an interval in days to a recurrence band, or null if it fits none. */
    private static String bandFor(double days) {
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

    private record GroupKey(UUID merchantId, UUID categoryId) {
    }

    // ── response records ───────────────────────────────────────────────────────

    /** One upcoming (or just-passed) date to act on. kind = due | renewal | warranty. */
    public record ExpiringItem(String documentId, String title, String category, String kind,
                               LocalDate date, long daysLeft, BigDecimal amount, String currency) {
    }

    /** A merchant+category that recurs on a regular cadence, with the predicted next date. */
    public record RecurringGroup(String merchant, String category, String categoryLabel, int occurrences,
                                 String cadence, BigDecimal averageAmount, String currency,
                                 LocalDate lastSeen, LocalDate nextExpected) {
    }
}
