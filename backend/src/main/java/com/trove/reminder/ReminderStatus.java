/*
 * ============================================================================
 *  ReminderStatus — lifecycle of a reminder
 * ============================================================================
 *  Purpose:        string constants for reminder.status values.
 *  Business use:    pending (waiting), sent (dispatched by the scheduler),
 *                  dismissed (the user cleared it - never mind), done (the user
 *                  handled it - and for a recurring reminder, this schedules the next).
 * ============================================================================
 */
package com.trove.reminder;

public final class ReminderStatus {

    public static final String PENDING = "pending";
    public static final String SENT = "sent";
    public static final String DISMISSED = "dismissed";
    public static final String DONE = "done";

    private ReminderStatus() {
    }
}
