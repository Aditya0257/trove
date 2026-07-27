/*
 * ============================================================================
 *  ReminderScheduler — periodically dispatches due reminders
 * ============================================================================
 *  Purpose:        on a fixed schedule, ask ReminderService to dispatch any
 *                  reminders whose date has arrived.
 *  Business use:    this is what makes reminders actually fire without anyone poking
 *                  the app — the "scheduled jobs" from DESIGN §3.
 *  Design:         @Scheduled fixed delay (config trove.reminder.scan-fixed-delay-ms).
 *                  Stateless and idempotent (dispatched reminders flip to 'sent'),
 *                  so overlapping/duplicate runs are harmless.
 * ============================================================================
 */
package com.trove.service.impl;
import com.trove.service.ReminderService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

@Component
public class ReminderScheduler {

    private final ReminderService reminderService;

    public ReminderScheduler(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    /** Scans for and dispatches due reminders on a fixed delay. */
    @Scheduled(fixedDelayString = "${trove.reminder.scan-fixed-delay-ms:3600000}")
    public void dispatchDueReminders() {
        reminderService.dispatchDue(LocalDate.now(ZoneOffset.UTC));
    }
}
