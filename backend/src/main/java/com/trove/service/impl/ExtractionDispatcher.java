/*
 * ============================================================================
 *  ExtractionDispatcher — offloads extraction onto the bounded executor
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  A thin async boundary: hand a document id to the extraction executor so the
 *  caller (upload request thread, or the reconciler) returns immediately.
 *
 *  Business use case
 *  -----------------
 *  Uploads must feel instant; extraction (a slow model call, later) runs in the
 *  background. This is the seam that makes that true.
 *
 *  Solution architecture
 *  ---------------------
 *  @Async("extractionExecutor") runs process on a pool thread. It is a SEPARATE
 *  bean from ExtractionWorker on purpose: calling worker.process() cross-bean lets
 *  the worker's @Transactional take effect (a self-invocation would bypass the
 *  proxy). Both the AFTER_COMMIT listener and the reconciler funnel through here so
 *  there is exactly one async entry point (DECISIONS.md → D3).
 *
 *  Reasoning & logic
 *  -----------------
 *  Exceptions are caught and logged here — an uncaught exception on an @Async void
 *  method would otherwise vanish. A failed run leaves extraction_confidence NULL, so
 *  the reconciler will retry it later.
 * ============================================================================
 */
package com.trove.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ExtractionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ExtractionDispatcher.class);

    private final ExtractionWorker worker;

    public ExtractionDispatcher(ExtractionWorker worker) {
        this.worker = worker;
    }

    /** Runs extraction on the extraction executor. Failures are logged, not thrown. */
    @Async("extractionExecutor")
    public void dispatch(UUID documentId) {
        try {
            worker.process(documentId);
        } catch (Exception e) {
            // Left un-extracted (confidence stays NULL) → the reconciler will retry.
            log.error("Async extraction failed for document {} - will be retried by the reconciler",
                    documentId, e);
        }
    }
}
