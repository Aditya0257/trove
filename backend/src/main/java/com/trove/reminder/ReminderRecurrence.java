/*
 * ============================================================================
 *  ReminderRecurrence — how often a reminder repeats
 * ============================================================================
 *  Purpose:        string constants for reminder.recurrence values, plus the rule
 *                  for computing the next occurrence's date.
 *  Business use:    rent every month, a subscription every year, a check-in every
 *                  week. When a recurring reminder is marked done, the next one is
 *                  scheduled automatically at the interval below.
 *  Design:         plain strings to match the DDL text exactly; 'none' means a
 *                  one-off reminder (the default, so existing rows are unchanged).
 * ============================================================================
 */
package com.trove.reminder;

import java.time.LocalDate;
import java.util.Set;

public final class ReminderRecurrence {

    public static final String NONE = "none";
    public static final String WEEKLY = "weekly";
    public static final String MONTHLY = "monthly";
    public static final String QUARTERLY = "quarterly";
    public static final String YEARLY = "yearly";

    public static final Set<String> ALL = Set.of(NONE, WEEKLY, MONTHLY, QUARTERLY, YEARLY);

    private ReminderRecurrence() {
    }

    /** True when this recurrence actually repeats (anything other than 'none'). */
    public static boolean repeats(String recurrence) {
        return recurrence != null && !NONE.equals(recurrence) && ALL.contains(recurrence);
    }

    /**
     * The next occurrence date at or after {@code notBefore}, starting from {@code from}.
     * Advances by the interval until it is strictly after {@code notBefore}, so a series
     * whose date has slipped into the past jumps straight to the next FUTURE date in one
     * step (rather than scheduling another already-overdue occurrence). Returns null for a
     * non-repeating recurrence.
     *
     * Example: a monthly reminder for 2026-01-10 marked done on 2026-03-20 -> 2026-04-10.
     */
    public static LocalDate next(String recurrence, LocalDate from, LocalDate notBefore) {
        if (!repeats(recurrence) || from == null) {
            return null;
        }
        LocalDate d = step(recurrence, from);
        while (notBefore != null && !d.isAfter(notBefore)) {
            d = step(recurrence, d);
        }
        return d;
    }

    private static LocalDate step(String recurrence, LocalDate d) {
        return switch (recurrence) {
            case WEEKLY -> d.plusWeeks(1);
            case MONTHLY -> d.plusMonths(1);
            case QUARTERLY -> d.plusMonths(3);
            case YEARLY -> d.plusYears(1);
            default -> d;
        };
    }
}
