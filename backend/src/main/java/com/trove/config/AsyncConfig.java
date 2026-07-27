/*
 * ============================================================================
 *  AsyncConfig — the bounded executor that runs document extraction off-thread
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Defines the "extractionExecutor" ThreadPoolTaskExecutor used by the extraction
 *  pipeline so uploads return immediately while OCR/field-extraction runs in the
 *  background.
 *
 *  Business use case
 *  -----------------
 *  A user snapping a bill should get an instant "received, we're reading it"
 *  response; the slow work (a vision model call, later) happens asynchronously.
 *  But "the app is disposable, the data is not" means async must be RELIABLE, not
 *  fire-and-forget.
 *
 *  Solution architecture
 *  ---------------------
 *  This executor is the heart of the production async design (DECISIONS.md → D3):
 *    • bounded core/max pool + bounded queue  → predictable memory on a tiny box
 *    • CallerRunsPolicy on saturation         → backpressure; work is NEVER dropped
 *                                                (the submitting thread runs it)
 *    • graceful shutdown (await termination)  → in-flight extraction finishes on
 *                                                a clean redeploy
 *  Crash safety (host killed mid-task) is handled separately by ExtractionReconciler.
 *
 *  Reasoning & logic
 *  -----------------
 *  Sizes come from ExtractionProperties so they are tunable per environment without
 *  a rebuild. Named threads ("trove-extract-") make thread dumps/logs readable.
 * ============================================================================
 */
package com.trove.config;

import com.trove.config.ExtractionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    private final ExtractionProperties props;

    public AsyncConfig(ExtractionProperties props) {
        this.props = props;
    }

    /**
     * Dedicated pool for extraction. Bean name "extractionExecutor" is referenced by
     * the @Async dispatch method so extraction never shares Spring's default pool.
     */
    @Bean("extractionExecutor")
    public ThreadPoolTaskExecutor extractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getExecutor().getCorePoolSize());
        executor.setMaxPoolSize(props.getExecutor().getMaxPoolSize());
        executor.setQueueCapacity(props.getExecutor().getQueueCapacity());
        executor.setThreadNamePrefix("trove-extract-");
        // Backpressure: when the pool + queue are full, run the task on the caller's
        // thread rather than dropping it or blowing up memory. No upload's extraction
        // is ever silently lost.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // On shutdown/redeploy, let in-flight extractions finish (bounded wait).
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
