/*
 * ============================================================================
 *  ExtractionEngine — walks the provider fallback chain, free-tier first
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Runs an extraction by trying each configured {provider, model, effort} step in
 *  order, returning the first result that clears the acceptance threshold. Skips
 *  steps whose circuit breaker is open, records quota failures, and always finishes
 *  with a usable result (best-effort, or the guaranteed stub).
 *
 *  Business use case
 *  -----------------
 *  This is the "never bound to one model, zero cost, works for years" core. It lets
 *  Trove route across free tiers (Gemini → Cloudflare → local Ollama → stub),
 *  automatically failover when a tier is exhausted or down, and never leave an
 *  upload unread (DECISIONS.md → D9).
 *
 *  Solution architecture
 *  ---------------------
 *  Resolves provider beans by name from the Spring context. Called by
 *  ExtractionWorker (inside the async, transactional extraction step). Time is
 *  injectable so the breaker cooldown logic is deterministically testable.
 *
 *  Reasoning & logic
 *  -----------------
 *  "First accepted wins" keeps latency and cost low (we stop at the first good
 *  result). If nothing clears the bar, we return the highest-confidence result seen
 *  (so a human still has something to confirm); if no step produced anything, we
 *  fall back to the stub, which never fails — the pipeline always completes.
 * ============================================================================
 */
package com.trove.extraction.engine;

import com.trove.extraction.ExtractionException;
import com.trove.extraction.ExtractionProperties;
import com.trove.extraction.ExtractionProvider;
import com.trove.extraction.ExtractionRequest;
import com.trove.extraction.ExtractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ExtractionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExtractionEngine.class);
    private static final String STUB = "stub";

    private final Map<String, ExtractionProvider> providers;
    private final ExtractionProperties props;
    private final ProviderCircuitBreaker breaker;

    public ExtractionEngine(Map<String, ExtractionProvider> providers,
                            ExtractionProperties props,
                            ProviderCircuitBreaker breaker) {
        this.providers = providers;
        this.props = props;
        this.breaker = breaker;
    }

    /** Runs the chain using the current wall clock. */
    public ExtractionOutcome run(byte[] fileBytes, String mimeType) {
        return run(fileBytes, mimeType, System.currentTimeMillis());
    }

    /** Runs the chain at a specific time (time is injected so cooldowns are testable). */
    public ExtractionOutcome run(byte[] fileBytes, String mimeType, long nowMillis) {
        ExtractionResult best = null;
        String bestProvider = null;
        String bestModel = null;
        // The attempt-trail feeds the Notice System's Developer surface (D23) — one
        // entry per step, in chain order, recording what happened and how long it took.
        List<ExtractionAttempt> attempts = new ArrayList<>();

        for (ExtractionProperties.Step step : props.getChain()) {
            String label = step.label();

            ExtractionProvider provider = providers.get(step.getProvider());
            if (provider == null) {
                log.warn("Extraction step '{}' skipped — no provider bean named '{}'",
                        label, step.getProvider());
                attempts.add(new ExtractionAttempt(label, step.getProvider(), step.getModel(),
                        ExtractionAttempt.SKIPPED_NO_BEAN, "no provider bean registered", null, 0));
                continue;
            }
            if (breaker.isOpen(label, nowMillis)) {
                log.info("Extraction step '{}' skipped — circuit breaker open", label);
                attempts.add(new ExtractionAttempt(label, step.getProvider(), step.getModel(),
                        ExtractionAttempt.SKIPPED_BREAKER, "circuit breaker open (recent failures)", null, 0));
                continue;
            }

            long startNanos = System.nanoTime();
            try {
                ExtractionResult result = provider.extract(fileBytes, mimeType,
                        new ExtractionRequest(step.getModel(), step.getEffort()));
                breaker.recordSuccess(label);
                long ms = elapsedMs(startNanos);

                if (result == null) {
                    attempts.add(new ExtractionAttempt(label, step.getProvider(), step.getModel(),
                            ExtractionAttempt.TRANSIENT, "provider returned no result", null, ms));
                    continue;
                }
                if (isAccepted(result)) {
                    log.info("Extraction step '{}' accepted (confidence={})", label, result.confidence());
                    attempts.add(new ExtractionAttempt(label, step.getProvider(), step.getModel(),
                            ExtractionAttempt.ACCEPTED, "cleared the acceptance bar", pct(result), ms));
                    return new ExtractionOutcome(result, step.getProvider(), step.getModel(), true, attempts);
                }
                // Not confident enough — keep as fallback if it's the best so far.
                if (best == null || confidence(result).compareTo(confidence(best)) > 0) {
                    best = result;
                    bestProvider = step.getProvider();
                    bestModel = step.getModel();
                }
                log.info("Extraction step '{}' below threshold (confidence={}) — trying next",
                        label, result.confidence());
                attempts.add(new ExtractionAttempt(label, step.getProvider(), step.getModel(),
                        ExtractionAttempt.BELOW_THRESHOLD, "below the acceptance bar", pct(result), ms));

            } catch (ExtractionException e) {
                long ms = elapsedMs(startNanos);
                if (e.isQuotaExhausted()) {
                    breaker.recordQuotaFailure(label, nowMillis,
                            props.getBreaker().getFailureThreshold(),
                            props.getBreaker().getCooldownSeconds());
                    log.warn("Extraction step '{}' quota-exhausted — {}", label, e.getMessage());
                    attempts.add(new ExtractionAttempt(label, step.getProvider(), step.getModel(),
                            ExtractionAttempt.QUOTA, "free daily allowance reached", null, ms));
                } else {
                    log.warn("Extraction step '{}' failed (transient) — {}", label, e.getMessage());
                    attempts.add(new ExtractionAttempt(label, step.getProvider(), step.getModel(),
                            ExtractionAttempt.TRANSIENT, shorten(e.getMessage()), null, ms));
                }
            } catch (Exception e) {
                log.warn("Extraction step '{}' errored unexpectedly — {}", label, e.toString());
                attempts.add(new ExtractionAttempt(label, step.getProvider(), step.getModel(),
                        ExtractionAttempt.ERROR, shorten(e.getClass().getSimpleName()), null, elapsedMs(startNanos)));
            }
        }

        // Nothing cleared the bar: return the best-effort result if we have one.
        if (best != null) {
            log.info("No step cleared the acceptance bar — using best-effort result from '{}'", bestProvider);
            return new ExtractionOutcome(best, bestProvider, bestModel, false, attempts);
        }

        // Absolute last resort: the stub never fails, so the pipeline always completes.
        ExtractionProvider stub = providers.get(STUB);
        if (stub != null) {
            log.info("No step produced a result — falling back to stub");
            return new ExtractionOutcome(stub.extract(fileBytes, mimeType), STUB, null, false, attempts);
        }

        throw new IllegalStateException("Extraction chain produced no result and no 'stub' provider is available");
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Confidence (0..1) as a whole percent for the trail, or null. */
    private static Integer pct(ExtractionResult result) {
        return result == null || result.confidence() == null
                ? null : (int) Math.round(result.confidence().doubleValue() * 100);
    }

    /** Trim a provider message for the trail (keeps it legible; carries no secrets). */
    private static String shorten(String s) {
        if (s == null || s.isBlank()) return "failed";
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private boolean isAccepted(ExtractionResult result) {
        return result.confidence() != null
                && result.confidence().doubleValue() >= props.getAcceptanceConfidence();
    }

    private BigDecimal confidence(ExtractionResult result) {
        return result.confidence() != null ? result.confidence() : BigDecimal.ZERO;
    }
}
