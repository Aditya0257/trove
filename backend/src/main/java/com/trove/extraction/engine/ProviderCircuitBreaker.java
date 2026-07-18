/*
 * ============================================================================
 *  ProviderCircuitBreaker — skip quota-exhausted chain steps for a cooldown
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Tracks, per chain-step label, how many consecutive quota failures have occurred
 *  and "opens" (temporarily disables) a step once a threshold is hit, until a
 *  cooldown elapses.
 *
 *  Business use case
 *  -----------------
 *  Free tiers hand out daily/minute quotas. Once a provider says "no more today",
 *  hammering it wastes latency on every upload. Opening the breaker makes the engine
 *  skip that step and fall straight to the next free option — the mechanism that
 *  lets Trove keep working for years across rotating free tiers (DECISIONS.md → D9).
 *
 *  Solution architecture
 *  ---------------------
 *  In-memory, thread-safe (ConcurrentHashMap) — no external state, fine for a single
 *  stateless instance. Time is passed in (nowMillis) so behavior is deterministic
 *  and unit-testable without sleeping.
 *
 *  Reasoning & logic
 *  -----------------
 *  A success resets the failure count and closes the breaker. Only quota failures
 *  count toward opening (transient errors don't), so a flaky network doesn't
 *  disable an otherwise-healthy free tier.
 * ============================================================================
 */
package com.trove.extraction.engine;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProviderCircuitBreaker {

    private static final class State {
        int consecutiveFailures;
        long openUntilMillis;
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    /** True if this step is currently open (should be skipped) at the given time. */
    public boolean isOpen(String label, long nowMillis) {
        State s = states.get(label);
        return s != null && nowMillis < s.openUntilMillis;
    }

    /** Clears failures and closes the breaker for a step that just succeeded. */
    public void recordSuccess(String label) {
        states.remove(label);
    }

    /**
     * Records a quota failure; opens the breaker for cooldownSeconds once
     * failureThreshold consecutive quota failures are reached.
     */
    public void recordQuotaFailure(String label, long nowMillis, int failureThreshold, long cooldownSeconds) {
        states.compute(label, (k, existing) -> {
            State s = existing != null ? existing : new State();
            s.consecutiveFailures++;
            if (s.consecutiveFailures >= failureThreshold) {
                s.openUntilMillis = nowMillis + cooldownSeconds * 1000L;
            }
            return s;
        });
    }
}
