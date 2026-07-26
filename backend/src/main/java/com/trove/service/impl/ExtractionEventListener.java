/*
 * ============================================================================
 *  ExtractionEventListener — triggers extraction after the upload commits
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Listens for DocumentUploadedEvent and dispatches extraction — but only AFTER the
 *  upload transaction has committed.
 *
 *  Business use case
 *  -----------------
 *  Guarantees extraction never runs against a row that doesn't durably exist yet —
 *  a subtle bug that would otherwise cause "document not found" flakes and lost
 *  extractions under load.
 *
 *  Solution architecture
 *  ---------------------
 *  @TransactionalEventListener(phase = AFTER_COMMIT) fires post-commit; it then
 *  calls the dispatcher, which offloads to the executor. Together with the
 *  reconciler this yields at-least-once extraction (DECISIONS.md → D3).
 *
 *  Reasoning & logic
 *  -----------------
 *  If the transaction rolls back, the event is discarded and no extraction is
 *  attempted — exactly right, since there is no committed document to extract.
 * ============================================================================
 */
package com.trove.service.impl;

import com.trove.event.DocumentUploadedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ExtractionEventListener {

    private final ExtractionDispatcher dispatcher;

    public ExtractionEventListener(ExtractionDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** After the upload commits, kick off async extraction for that document. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        dispatcher.dispatch(event.documentId());
    }
}
