/*
 * ============================================================================
 *  ReminderStatus — lifecycle of a reminder
 * ============================================================================
 *  Purpose:        string constants for reminder.status values.
 *  Business use:    pending (waiting), sent (dispatched by the scheduler),
 *                  dismissed (the user cleared it).
 * ============================================================================
 */
package com.trove.reminder;

public final class ReminderStatus {

    public static final String PENDING = "pending";
    public static final String SENT = "sent";
    public static final String DISMISSED = "dismissed";

    private ReminderStatus() {
    }
}
