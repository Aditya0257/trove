/*
 * ============================================================================
 *  ExtractionReconciler — crash-recovery sweep for un-extracted documents
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Finds documents whose extraction never completed (extraction_confidence IS NULL)
 *  and re-dispatches them — on startup and on a periodic timer.
 *
 *  Business use case
 *  -----------------
 *  This is the safety net behind the core principle. If the Oracle host is reclaimed
 *  or the process is killed mid-extraction, the in-flight work is not lost: on the
 *  next start (and every sweep) it is picked up and finished. That is what makes the
 *  async pipeline "at-least-once" rather than "best-effort" (DECISIONS.md → D3, D5).
 *
 *  Solution architecture
 *  ---------------------
 *  ApplicationRunner → runs once after the context is ready (recover from a crash).
 *  @Scheduled(fixedDelay) → runs continuously (recover from transient failures, e.g.
 *  storage/model blips that left confidence NULL). Both funnel through the same
 *  dispatcher/executor as live uploads, so backpressure and sizing are shared.
 *
 *  Reasoning & logic
 *  -----------------
 *  Uses the confidence-IS-NULL sentinel (D5) so no extra state is needed. The sweep
 *  is cheap because idx_document_hash/space indexes keep the table small and the
 *  worker's idempotency guard makes a redundant dispatch a no-op.
 * ============================================================================
 */
package com.trove.extraction;

import com.trove.document.Document;
import com.trove.document.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ExtractionReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExtractionReconciler.class);

    private final DocumentRepository documentRepository;
    private final ExtractionDispatcher dispatcher;

    public ExtractionReconciler(DocumentRepository documentRepository, ExtractionDispatcher dispatcher) {
        this.documentRepository = documentRepository;
        this.dispatcher = dispatcher;
    }

    /** On startup: re-dispatch anything left un-extracted by a prior crash. */
    @Override
    public void run(ApplicationArguments args) {
        reconcile("startup");
    }

    /** Periodically: re-dispatch anything still un-extracted (transient failures). */
    @Scheduled(fixedDelayString = "${trove.extraction.reconciler.fixed-delay-ms:300000}")
    public void scheduledSweep() {
        reconcile("scheduled");
    }

    private void reconcile(String trigger) {
        List<Document> pending = documentRepository.findByExtractionConfidenceIsNull();
        if (pending.isEmpty()) {
            return;
        }
        log.info("Extraction reconciler ({}) found {} un-extracted document(s) - re-dispatching",
                trigger, pending.size());
        pending.forEach(doc -> dispatcher.dispatch(doc.getId()));
    }
}
