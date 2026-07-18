/*
 * ============================================================================
 *  ReminderProperties — scheduler cadence + due-reminder lead time
 * ============================================================================
 *  Purpose:        binds trove.reminder.* (scan interval, lead days before a due
 *                  date to fire the reminder).
 *  Business use:    a bill reminder should arrive a few days BEFORE it's due, not
 *                  on the day — lead-days controls that.
 *  Design:         scan runs on a fixed delay; default hourly (cheap at this scale).
 * ============================================================================
 */
package com.trove.reminder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.reminder")
public class ReminderProperties {

    /** How often the scheduler scans for due reminders. */
    private long scanFixedDelayMs = 3_600_000; // 1h

    /** Days before a document's due date to fire its reminder. */
    private int leadDays = 3;

    public long getScanFixedDelayMs() { return scanFixedDelayMs; }
    public void setScanFixedDelayMs(long scanFixedDelayMs) { this.scanFixedDelayMs = scanFixedDelayMs; }

    public int getLeadDays() { return leadDays; }
    public void setLeadDays(int leadDays) { this.leadDays = leadDays; }
}
