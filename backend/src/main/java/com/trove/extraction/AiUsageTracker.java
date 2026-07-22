/*
 * ============================================================================
 *  AiUsageTracker — app-wide AI token consumption for the day
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  One Cloudflare Workers AI account backs the whole application, so its daily free
 *  allowance is shared by ALL users. This tracks the total tokens consumed today
 *  (extraction + search) across everyone, so the Developer surface can show a single
 *  global gauge rather than a misleading per-device count.
 *
 *  Solution architecture
 *  ---------------------
 *  A process-wide counter that rolls over at UTC midnight. Every provider that spends
 *  tokens calls add(); the request filter reads tokensToday() into a response header
 *  the clients display. In-memory by design (cheap, no dependency); it resets on
 *  restart — Cloudflare's own dashboard/analytics API remains the authoritative source
 *  for billing, and a persistent counter can be added later if needed.
 *
 *  Reasoning & logic
 *  -----------------
 *  Neurons (Cloudflare's real free-tier unit) aren't returned per request; tokens are.
 *  This counts tokens as a visible proxy for consumption, not as the hard limit.
 * ============================================================================
 */
package com.trove.extraction;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

@Component
public class AiUsageTracker {

    private LocalDate day = LocalDate.now(ZoneOffset.UTC);
    private long tokens = 0;

    /** Add tokens spent by an AI call, rolling the counter over at UTC midnight. */
    public synchronized void add(int spent) {
        rollIfNewDay();
        if (spent > 0) {
            tokens += spent;
        }
    }

    /** Total AI tokens consumed across the whole app so far today (UTC). */
    public synchronized long tokensToday() {
        rollIfNewDay();
        return tokens;
    }

    private void rollIfNewDay() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            tokens = 0;
        }
    }
}
