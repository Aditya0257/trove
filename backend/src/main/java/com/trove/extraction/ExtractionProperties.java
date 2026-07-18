/*
 * ============================================================================
 *  ExtractionProperties — extraction provider + async executor configuration
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Binds trove.extraction.*: which provider is active ('stub' | 'vision'), the
 *  bounded async pool sizing, and the reconciler sweep interval.
 *
 *  Business use case
 *  -----------------
 *  Extraction is the "read the document for the user" step. It must be swappable
 *  (stub now, real vision model later) and it must never lose an upload's
 *  extraction even if the host dies mid-run — the pool + reconciler settings here
 *  make that a config concern (DECISIONS.md → D3).
 *
 *  Solution architecture
 *  ---------------------
 *  - provider            → selects the ExtractionProvider bean at runtime.
 *  - executor.*          → sizes the ThreadPoolTaskExecutor in AsyncConfig.
 *  - reconciler.fixedDelayMs → how often ExtractionReconciler re-sweeps for
 *                          documents left un-extracted after a crash.
 *
 *  Reasoning & logic
 *  -----------------
 *  Defaults are modest (core=2, max=4, queue=100) because Trove serves ~50–100
 *  users on free-tier hardware — enough concurrency to stay responsive, small
 *  enough to not exhaust a tiny ARM box.
 * ============================================================================
 */
package com.trove.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.extraction")
public class ExtractionProperties {

    /** Active provider bean qualifier: 'stub' now, 'vision' later. */
    private String provider = "stub";

    private final Executor executor = new Executor();
    private final Reconciler reconciler = new Reconciler();

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public Executor getExecutor() { return executor; }
    public Reconciler getReconciler() { return reconciler; }

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
