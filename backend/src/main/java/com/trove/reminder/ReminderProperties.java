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

import java.util.List;

@Component
@ConfigurationProperties(prefix = "trove.reminder")
public class ReminderProperties {

    /** How often the scheduler scans for due reminders. */
    private long scanFixedDelayMs = 3_600_000; // 1h

    /** Legacy single lead time; kept for back-compat. Prefer leadDaysList. */
    private int leadDays = 3;

    /**
     * Days-before-due at which to fire a reminder. Defaults to a 7-day heads-up, a
     * 1-day nudge, and one on the day itself — so a warranty/bill/renewal is flagged
     * well ahead and again right before it lapses.
     */
    private List<Integer> leadDaysList = List.of(7, 1, 0);

    public long getScanFixedDelayMs() { return scanFixedDelayMs; }
    public void setScanFixedDelayMs(long scanFixedDelayMs) { this.scanFixedDelayMs = scanFixedDelayMs; }

    public int getLeadDays() { return leadDays; }
    public void setLeadDays(int leadDays) { this.leadDays = leadDays; }

    public List<Integer> getLeadDaysList() { return leadDaysList; }
    public void setLeadDaysList(List<Integer> leadDaysList) { this.leadDaysList = leadDaysList; }
}
