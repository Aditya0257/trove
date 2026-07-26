/*
 * ============================================================================
 *  ChatReindexJob — periodically embeds documents missing a current vector
 * ============================================================================
 *  Purpose:        guarantee eventual coverage of the semantic index — new uploads and
 *                  any docs added before embeddings existed get indexed within the hour.
 *  Business use:    everything in the vault becomes askable without manual action.
 *  Design:         bounded per run (a cap) so a big backlog is spread across runs and a
 *                  sweep never spikes cost/CPU. Opt-out via trove.chat.reindex-enabled.
 * ============================================================================
 */
package com.trove.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class ChatReindexJob {

    private static final Logger log = LoggerFactory.getLogger(ChatReindexJob.class);

    private final EmbeddingService embeddings;
    private final boolean enabled;
    private final int perRunCap;

    public ChatReindexJob(EmbeddingService embeddings,
                          @Value("${trove.chat.reindex-enabled:true}") boolean enabled,
                          @Value("${trove.chat.reindex-per-run:200}") int perRunCap) {
        this.embeddings = embeddings;
        this.enabled = enabled;
        this.perRunCap = perRunCap;
    }

    @Scheduled(fixedDelayString = "${trove.chat.reindex-fixed-delay-ms:3600000}", initialDelay = 30000)
    public void run() {
        if (!enabled) {
            return;
        }
        List<UUID> stale = embeddings.staleDocumentIds(perRunCap);
        int done = 0;
        for (UUID id : stale) {
            if (embeddings.index(id, null)) {
                done++;
            }
        }
        if (done > 0) {
            log.info("Chat reindex embedded {} document(s)", done);
        }
    }
}
