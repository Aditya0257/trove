/*
 * ============================================================================
 *  ExtractionProperties — extraction chain, executor, breaker, reconciler config
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Binds trove.extraction.*: the ordered provider fallback chain, the acceptance
 *  confidence threshold, the circuit-breaker settings, the bounded async pool, and
 *  the reconciler sweep interval.
 *
 *  Business use case
 *  -----------------
 *  Extraction must run at zero cost for years by routing across free tiers and
 *  degrading gracefully. All of that is expressed here as configuration so the
 *  routing can change per environment without a rebuild (DECISIONS.md → D9, D3).
 *
 *  Solution architecture
 *  ---------------------
 *  - chain               → ordered {provider, model, effort} steps the engine walks.
 *  - acceptanceConfidence → minimum confidence for a result to be accepted.
 *  - breaker.*           → when to skip a step after quota failures, and for how long.
 *  - executor.*          → sizes the ThreadPoolTaskExecutor in AsyncConfig.
 *  - reconciler.fixedDelayMs → crash-recovery sweep cadence.
 *
 *  Reasoning & logic
 *  -----------------
 *  Default chain is a single 'stub' step with acceptanceConfidence 0.0, so with no
 *  keys configured the pipeline behaves exactly like Slice 1. Add real steps (and
 *  raise the threshold) once free-tier keys/Ollama are available.
 * ============================================================================
 */
package com.trove.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "trove.extraction")
public class ExtractionProperties {

    /** Ordered fallback chain. First step that yields an accepted result wins. */
    private List<Step> chain = new ArrayList<>(List.of(Step.of("stub", null, null)));

    /** Minimum confidence (0..1) for a result to be "accepted" and end the chain. */
    private double acceptanceConfidence = 0.0;

    private final Breaker breaker = new Breaker();
    private final Executor executor = new Executor();
    private final Reconciler reconciler = new Reconciler();

    public List<Step> getChain() { return chain; }
    public void setChain(List<Step> chain) { this.chain = chain; }

    public double getAcceptanceConfidence() { return acceptanceConfidence; }
    public void setAcceptanceConfidence(double acceptanceConfidence) { this.acceptanceConfidence = acceptanceConfidence; }

    public Breaker getBreaker() { return breaker; }
    public Executor getExecutor() { return executor; }
    public Reconciler getReconciler() { return reconciler; }

    /** One step in the fallback chain: a provider driven with a specific model/effort. */
    public static class Step {
        /** Provider bean name: 'gemini' | 'ollama' | 'stub' | ... */
        private String provider;
        /** Model id for this step (null = provider default). */
        private String model;
        /** Optional effort hint the provider may interpret. */
        private String effort;

        public static Step of(String provider, String model, String effort) {
            Step s = new Step();
            s.provider = provider;
            s.model = model;
            s.effort = effort;
            return s;
        }

        /** Stable label used for logging and circuit-breaker keys, e.g. "gemini:flash". */
        public String label() {
            return provider + ":" + (model == null || model.isBlank() ? "default" : model);
        }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getEffort() { return effort; }
        public void setEffort(String effort) { this.effort = effort; }
    }

    /** Per-step circuit breaker: skip a step for a cooldown after repeated quota failures. */
    public static class Breaker {
        private int failureThreshold = 2;
        private long cooldownSeconds = 300;

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }

        public long getCooldownSeconds() { return cooldownSeconds; }
        public void setCooldownSeconds(long cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    }

    /** Bounded async pool for extraction work. */
    public static class Executor {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 100;

        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }

        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }

        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    /** Periodic crash-recovery sweep settings. */
    public static class Reconciler {
        private long fixedDelayMs = 300_000;

        public long getFixedDelayMs() { return fixedDelayMs; }
        public void setFixedDelayMs(long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
    }
}
