/*
 * ============================================================================
 *  ExtractionAttempt — one step's record in the provider-chain trail (D23)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Captures what happened at a single chain step: which provider/model ran, the
 *  outcome (accepted / below-threshold / quota / transient / error / skipped), a
 *  short human reason, the confidence it returned, and how long it took.
 *
 *  Business use case
 *  -----------------
 *  This is the raw material for the Notice System's Developer surface (D23) — the web
 *  console and the in-app drawers render the trail so you can literally watch the
 *  free-tier fallback chain work ("cloudflare quota → stub"), with timings.
 *
 *  Solution architecture
 *  ---------------------
 *  Immutable record collected by ExtractionEngine.run(...) and folded into
 *  ExtractionOutcome. `toMap()` gives a plain, JSON-friendly view stored under a
 *  document's `extra.extractionMeta.attempts` (and thus into the sidecar, so the trail
 *  survives a DB rebuild). Contains NO secrets — provider name, status, timing only.
 * ============================================================================
 */
package com.trove.extraction.engine;

import java.util.LinkedHashMap;
import java.util.Map;

public record ExtractionAttempt(
        String label,        // e.g. "cloudflare:default"
        String provider,     // e.g. "cloudflare"
        String model,        // may be null
        String status,       // one of the STATUS_* constants
        String reason,       // short, human, no secrets
        Integer confidencePct,
        long latencyMs
) {
    public static final String ACCEPTED = "ACCEPTED";
    public static final String BELOW_THRESHOLD = "BELOW_THRESHOLD";
    public static final String QUOTA = "QUOTA";
    public static final String TRANSIENT = "TRANSIENT";
    public static final String ERROR = "ERROR";
    public static final String SKIPPED_NO_BEAN = "SKIPPED_NO_BEAN";
    public static final String SKIPPED_BREAKER = "SKIPPED_BREAKER";

    /** Plain, ordered, JSON-friendly view for the sidecar + client Developer surfaces. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("provider", provider);
        m.put("model", model);
        m.put("status", status);
        m.put("reason", reason);
        m.put("confidencePct", confidencePct);
        m.put("latencyMs", latencyMs);
        return m;
    }
}
