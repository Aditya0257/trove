/*
 * ============================================================================
 *  ReminderType — the kinds of reminders Trove tracks
 * ============================================================================
 *  Purpose:        string constants for reminder.type values.
 *  Business use:    due (a bill's payment date), renewal (policy/subscription), and
 *                  warranty_expiry (a purchase's warranty running out).
 *  Design:         plain strings to match the DDL text exactly.
 * ============================================================================
 */
package com.trove.enums;

import java.util.Set;

public final class ReminderType {

    public static final String DUE = "due";
    public static final String RENEWAL = "renewal";
    public static final String WARRANTY_EXPIRY = "warranty_expiry";

    public static final Set<String> ALL = Set.of(DUE, RENEWAL, WARRANTY_EXPIRY);

    private ReminderType() {
    }
}
