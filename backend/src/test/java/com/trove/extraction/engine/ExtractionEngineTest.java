/*
 * ============================================================================
 *  ExtractionEngineTest — verifies the fallback chain behavior
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Exercises ExtractionEngine deterministically with fake providers: first-accepted
 *  wins, low-confidence fallthrough, best-effort when nothing clears the bar,
 *  guaranteed stub fallback, and circuit-breaker skipping after quota failures.
 *
 *  Business use case
 *  -----------------
 *  This is the "never bound to one model, keep working for years on free tiers"
 *  logic. These tests prove that routing/failover behaves correctly without needing
 *  any API key or network — the valuable, novel part of Slice 2 (DECISIONS.md → D9).
 *
 *  Design
 *  ------
 *  Pure JUnit (no Spring context, no DB). Time is injected via run(..., nowMillis)
 *  so cooldown behavior is tested without sleeping.
 * ============================================================================
 */
package com.trove.extraction.engine;

import com.trove.extraction.ExtractionException;
import com.trove.extraction.ExtractionProperties;
import com.trove.extraction.ExtractionProvider;
import com.trove.extraction.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractionEngineTest {

    private static final byte[] BYTES = "doc".getBytes();
    private static final String MIME = "image/jpeg";

    // ── fakes ──────────────────────────────────────────────────────────────

    /** A provider whose behavior (return or throw) and call count we control. */
    static final class FakeProvider implements ExtractionProvider {
        int calls = 0;
        private final Supplier<ExtractionResult> behavior;

        FakeProvider(Supplier<ExtractionResult> behavior) {
            this.behavior = behavior;
        }

        @Override
        public ExtractionResult extract(byte[] fileBytes, String mimeType) {
            calls++;
            return behavior.get();
        }
    }

    private static ExtractionResult result(String category, double confidence) {
        return new ExtractionResult(category, "Merchant", null, null, "INR", null,
                List.of(), "raw", new HashMap<>(), BigDecimal.valueOf(confidence));
    }

    private static ExtractionProperties props(double acceptance, ExtractionProperties.Step... chain) {
        ExtractionProperties p = new ExtractionProperties();
        p.setAcceptanceConfidence(acceptance);
        p.setChain(List.of(chain));
        return p;
    }

    private static ExtractionProperties.Step step(String provider) {
        return ExtractionProperties.Step.of(provider, null, null);
    }

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    void firstAcceptedStepWins() {
        Map<String, ExtractionProvider> providers = new HashMap<>();
        FakeProvider a = new FakeProvider(() -> result("shopping", 0.90));
        FakeProvider b = new FakeProvider(() -> result("food", 0.95));
        providers.put("a", a);
        providers.put("b", b);

        ExtractionEngine engine = new ExtractionEngine(providers,
                props(0.5, step("a"), step("b")), new ProviderCircuitBreaker());

        ExtractionOutcome out = engine.run(BYTES, MIME, 1000L);

        assertEquals("a", out.provider());
        assertTrue(out.accepted());
        assertEquals(1, a.calls);
        assertEquals(0, b.calls, "b must not be called once a is accepted");
    }

    @Test
    void lowConfidenceFallsThroughToNextStep() {
        Map<String, ExtractionProvider> providers = new HashMap<>();
        providers.put("a", new FakeProvider(() -> result("shopping", 0.30)));
        providers.put("b", new FakeProvider(() -> result("food", 0.80)));

        ExtractionEngine engine = new ExtractionEngine(providers,
                props(0.60, step("a"), step("b")), new ProviderCircuitBreaker());

        ExtractionOutcome out = engine.run(BYTES, MIME, 1000L);

        assertEquals("b", out.provider());
        assertTrue(out.accepted());
    }

    @Test
    void bestEffortWhenNothingClearsTheBar() {
        Map<String, ExtractionProvider> providers = new HashMap<>();
        providers.put("a", new FakeProvider(() -> result("shopping", 0.30)));
        providers.put("b", new FakeProvider(() -> result("food", 0.50)));

        ExtractionEngine engine = new ExtractionEngine(providers,
                props(0.90, step("a"), step("b")), new ProviderCircuitBreaker());

        ExtractionOutcome out = engine.run(BYTES, MIME, 1000L);

        assertEquals("b", out.provider(), "highest-confidence result is used as best-effort");
        assertFalse(out.accepted());
    }

    @Test
    void fallsBackToStubWhenEveryStepFails() {
        Map<String, ExtractionProvider> providers = new HashMap<>();
        providers.put("a", new FakeProvider(() -> {
            throw ExtractionException.transientError("a", "boom", null);
        }));
        providers.put("stub", new FakeProvider(() -> result("shopping", 0.50)));

        // Chain only lists 'a'; stub is the engine's guaranteed last resort.
        ExtractionEngine engine = new ExtractionEngine(providers,
                props(0.90, step("a")), new ProviderCircuitBreaker());

        ExtractionOutcome out = engine.run(BYTES, MIME, 1000L);

        assertEquals("stub", out.provider());
        assertFalse(out.accepted());
    }

    @Test
    void quotaFailuresOpenBreakerAndSkipStep() {
        Map<String, ExtractionProvider> providers = new HashMap<>();
        FakeProvider a = new FakeProvider(() -> {
            throw ExtractionException.quota("a", "429", null);
        });
        FakeProvider b = new FakeProvider(() -> result("food", 0.95));
        providers.put("a", a);
        providers.put("b", b);

        // failure-threshold defaults to 2, cooldown 300s.
        ExtractionEngine engine = new ExtractionEngine(providers,
                props(0.5, step("a"), step("b")), new ProviderCircuitBreaker());

        engine.run(BYTES, MIME, 1_000L);   // a quota failure #1
        engine.run(BYTES, MIME, 2_000L);   // a quota failure #2 -> breaker opens
        ExtractionOutcome out = engine.run(BYTES, MIME, 3_000L); // a should be skipped

        assertEquals(2, a.calls, "a must be skipped once its breaker is open");
        assertEquals("b", out.provider());
        assertTrue(out.accepted());
    }

    @Test
    void breakerReopensAfterCooldown() {
        Map<String, ExtractionProvider> providers = new HashMap<>();
        FakeProvider a = new FakeProvider(() -> {
            throw ExtractionException.quota("a", "429", null);
        });
        providers.put("a", a);
        providers.put("stub", new FakeProvider(() -> result("shopping", 0.50)));

        ExtractionProperties p = props(0.5, step("a"));
        p.getBreaker().setFailureThreshold(1);
        p.getBreaker().setCooldownSeconds(300);
        ExtractionEngine engine = new ExtractionEngine(providers, p, new ProviderCircuitBreaker());

        engine.run(BYTES, MIME, 1_000L);            // opens (threshold 1)
        engine.run(BYTES, MIME, 2_000L);            // still open -> a skipped
        assertEquals(1, a.calls);

        // After cooldown (300s = 300_000ms) a is tried again.
        engine.run(BYTES, MIME, 1_000L + 300_001L);
        assertEquals(2, a.calls, "a must be retried after the cooldown elapses");
    }
}
