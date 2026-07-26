/*
 * ============================================================================
 *  ExtractionRequest — per-call model/effort selection for a provider
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Carries the model name and effort level the engine wants a provider to use for
 *  a single extraction call, so one provider bean can be driven with different
 *  models across chain steps.
 *
 *  Business use case
 *  -----------------
 *  The fallback chain needs "same provider, different model" as a first-class move
 *  (e.g. Gemini flash → Gemini flash-lite) to squeeze the most out of each free
 *  tier before switching providers. This record is how a step tells the provider
 *  which model/effort to use.
 *
 *  Solution architecture
 *  ---------------------
 *  Passed by ExtractionEngine into ExtractionProvider.extract(bytes, mime, request).
 *  A null model means "provider default". See DECISIONS.md → D9.
 *
 *  Reasoning & logic
 *  -----------------
 *  effort is a free-form string (e.g. "low"/"high", or provider-specific) so each
 *  provider can interpret it without a shared enum constraining future providers.
 * ============================================================================
 */
package com.trove.dto;

public record ExtractionRequest(String model, String effort) {

    /** A request with no model/effort override — provider uses its own default. */
    public static ExtractionRequest defaults() {
        return new ExtractionRequest(null, null);
    }
}
