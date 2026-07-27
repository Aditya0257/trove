/*
 * ============================================================================
 *  ChatIndexListener — (re)embeds a document when its content settles
 * ============================================================================
 *  Purpose:        keep the semantic index current without coupling documents/extraction
 *                  to the chat feature.
 *  Business use:    a confirmed document (final, human-verified fields) is immediately
 *                  askable. New/needs-review docs are caught by the hourly sweep.
 *  Design:         AFTER_COMMIT so the row is durable before we embed; best-effort inside
 *                  EmbeddingService. Embedding cost is negligible (~0.002 neurons/doc).
 * ============================================================================
 */
package com.trove.service.impl;
import com.trove.service.EmbeddingService;

import com.trove.event.DocumentConfirmedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ChatIndexListener {

    private final EmbeddingService embeddings;

    public ChatIndexListener(EmbeddingService embeddings) {
        this.embeddings = embeddings;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConfirmed(DocumentConfirmedEvent event) {
        embeddings.index(event.documentId(), null);
    }
}
