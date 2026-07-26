/*
 * ============================================================================
 *  ExtractionException — a provider failed to extract
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Signals that a specific provider step failed, distinguishing quota/rate-limit
 *  exhaustion (which should open the circuit breaker) from a transient error.
 *
 *  Business use case
 *  -----------------
 *  Free tiers fail in two very different ways: "you're out of quota for the day"
 *  (stop trying this step for a while) vs "a network blip" (just move on). Telling
 *  them apart is what lets the engine stop hammering an exhausted free tier while
 *  still retrying flaky ones — key to running for years on free tiers.
 *
 *  Solution architecture
 *  ---------------------
 *  Thrown by ExtractionProvider implementations; caught by ExtractionEngine, which
 *  records a quota failure against the circuit breaker or falls through on a
 *  transient one. See DECISIONS.md → D9.
 *
 *  Reasoning & logic
 *  -----------------
 *  quotaExhausted is the load-bearing flag; providers set it when they see HTTP 429
 *  or an explicit quota/limit response.
 * ============================================================================
 */
package com.trove.exception;

public class ExtractionException extends RuntimeException {

    private final String providerLabel;
    private final boolean quotaExhausted;

    public ExtractionException(String providerLabel, boolean quotaExhausted, String message, Throwable cause) {
        super(message, cause);
        this.providerLabel = providerLabel;
        this.quotaExhausted = quotaExhausted;
    }

    /** The provider hit a rate limit / quota — skip it for a cooldown. */
    public static ExtractionException quota(String providerLabel, String message, Throwable cause) {
        return new ExtractionException(providerLabel, true, message, cause);
    }

    /** A transient failure (network, 5xx, parse) — just move to the next step. */
    public static ExtractionException transientError(String providerLabel, String message, Throwable cause) {
        return new ExtractionException(providerLabel, false, message, cause);
    }

    public String getProviderLabel() {
        return providerLabel;
    }

    public boolean isQuotaExhausted() {
        return quotaExhausted;
    }
}
