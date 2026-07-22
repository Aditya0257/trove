/*
 * ============================================================================
 *  ExtractionOutcome — the engine's result, the winning step, and the full trail
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Pairs the ExtractionResult with the provider + model that produced it, whether it
 *  passed the acceptance gate, and the complete chain attempt-trail. Derives the
 *  two-channel Notice (D23) that tells the user + developer what happened.
 *
 *  Business use case
 *  -----------------
 *  Provenance + legibility: the worker records provider/model on the document AND a
 *  rich `extractionMeta` (trail + notice) so both the human reviewer ("we couldn't
 *  read this — add it yourself") and the developer surface ("cloudflare quota → stub,
 *  812ms") see exactly what the free-tier chain did. See DECISIONS.md → D9, D23.
 *
 *  Solution architecture
 *  ---------------------
 *  Returned by ExtractionEngine.run(...). `metaMap()` produces the plain JSON view
 *  stored under `extra.extractionMeta` (so it rides into the sidecar and survives a DB
 *  rebuild). `toNotice()` produces the ApiNotice clients render.
 *
 *  Reasoning & logic
 *  -----------------
 *  The notice is derived, not guessed: a stub win caused by a QUOTA attempt is a calm
 *  "auto-fill paused for today"; a stub win with no successful read is "we couldn't
 *  read it — add it yourself"; a below-threshold best-effort is "double-check our
 *  guess". Never leaks secrets — provider names, statuses, timings only.
 * ============================================================================
 */
package com.trove.extraction.engine;

import com.trove.common.notice.ApiNotice;
import com.trove.common.notice.NoticeLevel;
import com.trove.extraction.ExtractionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ExtractionOutcome(
        ExtractionResult result,
        String provider,
        String model,
        boolean accepted,
        List<ExtractionAttempt> attempts
) {
    private static final String STUB = "stub";

    /** The winning provider was the last-resort stub — nothing really read the doc. */
    public boolean fellBackToStub() {
        return STUB.equalsIgnoreCase(provider);
    }

    /** True if a real provider hit its quota anywhere in the trail. */
    private boolean anyQuota() {
        return attempts != null && attempts.stream()
                .anyMatch(a -> ExtractionAttempt.QUOTA.equals(a.status()));
    }

    private Integer confidencePct() {
        if (result == null || result.confidence() == null) return null;
        return (int) Math.round(result.confidence().doubleValue() * 100);
    }

    /** The two-channel notice for this outcome (D23). */
    public ApiNotice toNotice() {
        Integer pct = confidencePct();
        if (fellBackToStub()) {
            if (anyQuota()) {
                return ApiNotice.of(NoticeLevel.WARNING, "EXTRACTION_QUOTA",
                        "Auto-fill is paused for today. Add the details from your photo; "
                                + "everything else works normally.",
                        "AI extraction chain exhausted its free daily allowance (quota); "
                                + "fell back to stub → the document is in needs_review.");
            }
            return ApiNotice.of(NoticeLevel.WARNING, "EXTRACTION_FALLBACK",
                    "We couldn't read this one automatically. Please add the details from your photo.",
                    "No provider produced a usable read; fell back to stub → needs_review.");
        }
        if (!accepted) {
            return ApiNotice.of(NoticeLevel.WARNING, "EXTRACTION_LOW_CONFIDENCE",
                    "We took our best guess at the details. Please double-check them.",
                    "Best-effort result from " + provider
                            + (pct != null ? " (confidence " + pct + "%, below the acceptance bar)." : "."));
        }
        return ApiNotice.of(NoticeLevel.SUCCESS, "EXTRACTION_OK",
                "We read your document and pre-filled the details. Please review before confirming.",
                "Accepted from " + provider + (pct != null ? " at " + pct + "% confidence." : "."));
    }

    /** Total AI tokens across all attempts that reported them. */
    public long totalTokens() {
        return attempts == null ? 0
                : attempts.stream().filter(a -> a.tokens() != null).mapToLong(ExtractionAttempt::tokens).sum();
    }

    /** Total AI neurons across all attempts that reported them. */
    public double totalNeurons() {
        return attempts == null ? 0
                : attempts.stream().filter(a -> a.neurons() != null).mapToDouble(ExtractionAttempt::neurons).sum();
    }

    /** Plain JSON-friendly view stored under document.extra.extractionMeta. */
    public Map<String, Object> metaMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("model", model);
        m.put("accepted", accepted);
        m.put("fellBack", fellBackToStub());
        m.put("confidencePct", confidencePct());
        List<Map<String, Object>> trail = new ArrayList<>();
        if (attempts != null) {
            attempts.forEach(a -> trail.add(a.toMap()));
        }
        m.put("attempts", trail);
        return m;
    }
}
