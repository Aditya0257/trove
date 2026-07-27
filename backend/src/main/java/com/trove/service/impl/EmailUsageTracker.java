/*
 * ============================================================================
 *  EmailUsageTracker — persistent, per-UTC-day outbound email accounting
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  One Brevo account backs the whole app, and its free tier is 300 emails/day
 *  shared by everyone. This records how many we've sent per UTC day in the
 *  email_usage table (V26) so the sender can stop cleanly at the cap (rather than
 *  silently burn it or let Brevo start rejecting), and the Developer gauge can show
 *  the remaining daily allowance.
 *
 *  Solution architecture
 *  ---------------------
 *  A tiny JdbcTemplate upsert bumps the day's row on each accepted send. Mirrors
 *  AiUsageTracker, but global-only: email is a whole-app daily budget, not per user.
 *  Resets naturally at 00:00 UTC when the day key rolls over.
 * ============================================================================
 */
package com.trove.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;

@Component
public class EmailUsageTracker {

    private final JdbcTemplate jdbc;

    /** Brevo free-tier allowance, shared by the whole app (emails/day). */
    private final int dailyLimit;

    public EmailUsageTracker(JdbcTemplate jdbc,
                             @Value("${trove.email.daily-limit:300}") int dailyLimit) {
        this.jdbc = jdbc;
        this.dailyLimit = dailyLimit;
    }

    public int dailyLimit() {
        return dailyLimit;
    }

    /** How many emails the app has sent so far today (UTC). */
    public int sentToday() {
        return jdbc.query(
                "select sent from email_usage where day = ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                LocalDate.now(ZoneOffset.UTC));
    }

    /** True if there's still daily allowance left to send another email. */
    public boolean canSend() {
        return sentToday() < dailyLimit;
    }

    /**
     * Record one accepted send against today's total. Runs in its OWN transaction
     * (REQUIRES_NEW) so a scheduled/read-only caller can't poison it, and a sent
     * email stays counted even if the surrounding request later fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record() {
        jdbc.update(
                "insert into email_usage (day, sent) values (?, 1) "
                        + "on conflict (day) do update set sent = email_usage.sent + 1",
                LocalDate.now(ZoneOffset.UTC));
    }
}
