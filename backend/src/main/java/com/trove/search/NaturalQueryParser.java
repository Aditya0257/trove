/*
 * ============================================================================
 *  NaturalQueryParser — maps a plain-English query to structured filters
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Turns phrases like "my last water bill", "all Nike purchases", or "toll receipts
 *  from June" into a SearchQuery (category, date range, text, latest-only, limit).
 *
 *  Business use case
 *  -----------------
 *  Natural search is a headline feature. Doing it with rules (not an LLM) keeps it
 *  free and instant, which fits the zero-cost goal. The result is transparent — the
 *  interpreted filters are returned to the caller.
 *
 *  Solution architecture
 *  ---------------------
 *  Deterministic keyword/entity extraction: category synonyms, month names, a year,
 *  "last/latest" (→ newest one), "all" (→ no cap), and leftover significant words as
 *  a free-text match. Kept behind its own class so it could later be swapped for an
 *  LLM-backed parser without touching SearchService.
 *
 *  Reasoning & logic
 *  -----------------
 *  First category synonym wins. Recognized tokens (category words, months, year,
 *  qualifiers, stopwords) are removed; whatever remains becomes the text term.
 * ============================================================================
 */
package com.trove.search;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class NaturalQueryParser {

    private static final Set<String> STOPWORDS = Set.of(
            "my", "the", "a", "an", "bill", "bills", "receipt", "receipts", "show", "me",
            "find", "of", "for", "in", "on", "please", "get", "list", "all", "last",
            "latest", "recent", "most", "this", "give", "see", "want", "to", "from",
            "month", "months", "year", "years", "week", "weeks", "day", "days");

    private static final Set<String> LATEST_WORDS = Set.of("last", "latest", "recent");

    /** Synonym token → category code (checked in order; first match wins). */
    private static final Map<String, String> CATEGORY_SYNONYMS = new LinkedHashMap<>();

    static {
        CATEGORY_SYNONYMS.put("electricity", "electricity");
        CATEGORY_SYNONYMS.put("electric", "electricity");
        CATEGORY_SYNONYMS.put("power", "electricity");
        CATEGORY_SYNONYMS.put("water", "water");
        CATEGORY_SYNONYMS.put("gas", "gas");
        CATEGORY_SYNONYMS.put("internet", "internet");
        CATEGORY_SYNONYMS.put("broadband", "internet");
        CATEGORY_SYNONYMS.put("wifi", "internet");
        CATEGORY_SYNONYMS.put("mobile", "mobile");
        CATEGORY_SYNONYMS.put("phone", "mobile");
        CATEGORY_SYNONYMS.put("recharge", "mobile");
        CATEGORY_SYNONYMS.put("insurance", "insurance");
        CATEGORY_SYNONYMS.put("policy", "insurance");
        CATEGORY_SYNONYMS.put("premium", "insurance");
        CATEGORY_SYNONYMS.put("medical", "medical");
        CATEGORY_SYNONYMS.put("medicine", "medical");
        CATEGORY_SYNONYMS.put("pharmacy", "medical");
        CATEGORY_SYNONYMS.put("hospital", "medical");
        CATEGORY_SYNONYMS.put("travel", "travel");
        CATEGORY_SYNONYMS.put("flight", "travel");
        CATEGORY_SYNONYMS.put("train", "travel");
        CATEGORY_SYNONYMS.put("toll", "travel");
        CATEGORY_SYNONYMS.put("taxi", "travel");
        CATEGORY_SYNONYMS.put("fuel", "travel");
        CATEGORY_SYNONYMS.put("petrol", "travel");
        CATEGORY_SYNONYMS.put("food", "food");
        CATEGORY_SYNONYMS.put("restaurant", "food");
        CATEGORY_SYNONYMS.put("grocery", "food");
        CATEGORY_SYNONYMS.put("groceries", "food");
        CATEGORY_SYNONYMS.put("rent", "rent");
        CATEGORY_SYNONYMS.put("subscription", "subscription");
        CATEGORY_SYNONYMS.put("tax", "tax");
        CATEGORY_SYNONYMS.put("shopping", "shopping");
        CATEGORY_SYNONYMS.put("purchase", "shopping");
        CATEGORY_SYNONYMS.put("purchases", "shopping");
        CATEGORY_SYNONYMS.put("order", "shopping");
    }

    private static final Map<String, Integer> MONTHS = new LinkedHashMap<>();

    static {
        String[] full = {"january", "february", "march", "april", "may", "june",
                "july", "august", "september", "october", "november", "december"};
        String[] abbr = {"jan", "feb", "mar", "apr", "may", "jun",
                "jul", "aug", "sep", "oct", "nov", "dec"};
        for (int i = 0; i < 12; i++) {
            MONTHS.put(full[i], i + 1);
            MONTHS.putIfAbsent(abbr[i], i + 1);
        }
    }

    /** Parses free text into a SearchQuery (confirmed-or-not; caller may add status). */
    public SearchQuery parse(String raw) {
        SearchQuery q = new SearchQuery();
        if (raw == null || raw.isBlank()) {
            q.setLimit(50);
            return q;
        }
        String lower = raw.toLowerCase();
        boolean all = lower.contains("all");
        // "last month"/"last year" are relative periods, not "the single latest one".
        boolean relativeLast = lower.contains("last month") || lower.contains("last year");
        boolean latest = !relativeLast && LATEST_WORDS.stream().anyMatch(lower::contains);

        String[] tokens = lower.split("[^a-z0-9]+");

        // Relative periods first (multi-word phrases).
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        applyRelativePeriods(lower, today, q);

        Integer month = null;
        Integer year = null;
        String category = null;
        List<String> leftover = new ArrayList<>();

        for (String t : tokens) {
            if (t.isBlank()) {
                continue;
            }
            if (MONTHS.containsKey(t)) {
                month = MONTHS.get(t);
                continue;
            }
            if (t.matches("(19|20)\\d{2}")) {
                year = Integer.parseInt(t);
                continue;
            }
            if (category == null && CATEGORY_SYNONYMS.containsKey(t)) {
                category = CATEGORY_SYNONYMS.get(t);
                continue;
            }
            if (STOPWORDS.contains(t) || t.length() < 2) {
                continue;
            }
            leftover.add(t);
        }

        if (category != null) {
            q.setCategoryCode(category);
        }

        // Explicit month/year override any relative period detected above.
        if (month != null) {
            int y = year != null ? year : today.getYear();
            LocalDate from = LocalDate.of(y, month, 1);
            q.setDateFrom(from);
            q.setDateTo(from.withDayOfMonth(from.lengthOfMonth()));
        } else if (year != null) {
            q.setDateFrom(LocalDate.of(year, 1, 1));
            q.setDateTo(LocalDate.of(year, 12, 31));
        }

        if (!leftover.isEmpty()) {
            q.setText(String.join(" ", leftover));
        }

        if (latest) {
            q.setLatestOnly(true);
            q.setLimit(1);
        } else {
            q.setLimit(all ? 200 : 50);
        }
        return q;
    }

    private void applyRelativePeriods(String lower, LocalDate today, SearchQuery q) {
        if (lower.contains("last month")) {
            LocalDate first = today.minusMonths(1).withDayOfMonth(1);
            q.setDateFrom(first);
            q.setDateTo(first.withDayOfMonth(first.lengthOfMonth()));
        } else if (lower.contains("this month")) {
            LocalDate first = today.withDayOfMonth(1);
            q.setDateFrom(first);
            q.setDateTo(first.withDayOfMonth(first.lengthOfMonth()));
        } else if (lower.contains("last year")) {
            int y = today.getYear() - 1;
            q.setDateFrom(LocalDate.of(y, 1, 1));
            q.setDateTo(LocalDate.of(y, 12, 31));
        } else if (lower.contains("this year")) {
            int y = today.getYear();
            q.setDateFrom(LocalDate.of(y, 1, 1));
            q.setDateTo(LocalDate.of(y, 12, 31));
        }
    }
}
