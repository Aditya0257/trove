/*
 * ============================================================================
 *  UsageOverview — free-tier usage across every backing service, for the gauge
 * ============================================================================
 *  Purpose:  one payload the Developer drawer renders as a stack of meters, so the
 *            shared free-tier limits are visible and honest before going live.
 *  Design:   two daily-reset pools (AI neurons, email sends) carry `dailyResetAt`
 *            (the next 00:00 UTC instant) so the client can show an exact reset time
 *            and countdown; the storage meters are running totals with no reset.
 *            Vendor names stay out of here — the client supplies neutral labels.
 * ============================================================================
 */
package com.trove.dto;

import java.time.Instant;

public record UsageOverview(
        Instant dailyResetAt,
        Ai ai,
        Email email,
        Store storage,
        Store database,
        Mirror mirror) {

    /** Workers AI: shared daily neuron pool, plus the caller's own slice. Daily. */
    public record Ai(int limitNeurons, int perUserLimitNeurons,
                     double globalNeurons, long globalTokens,
                     double userNeurons, long userTokens) {
    }

    /** Outbound email: shared daily send allowance. Daily. */
    public record Email(int dailyLimit, int sentToday) {
    }

    /** A running-total storage meter (bytes used against a free-tier ceiling). */
    public record Store(long usedBytes, long limitBytes) {
    }

    /** The independent mirror copy; only meaningful when a mirror is configured. */
    public record Mirror(boolean enabled, long usedBytes, long limitBytes) {
    }
}
