/*
 * ============================================================================
 *  EmbeddingProvider — swappable text→vector embedding (D9 philosophy)
 * ============================================================================
 *  Purpose:        one call in (text), a fixed-length float vector out. Same swappable
 *                  interface idea as ExtractionProvider so the model is never welded in.
 *  Business use:    powers semantic retrieval for "Ask your vault".
 *  Design:         implementations: a free offline Stub (dev/tests, no network) and a
 *                  Cloudflare Workers AI provider (bge-base). The active one is chosen
 *                  by EmbeddingService based on config.
 * ============================================================================
 */
package com.trove.chat;

public interface EmbeddingProvider {

    /** Embeds text into a vector of length {@link #dimensions()}. Never returns null. */
    float[] embed(String text, java.util.UUID billToUserId);

    /** The model identifier stored alongside each embedding (so we can re-embed on change). */
    String model();

    /** Vector length this provider produces (must match the DB column). */
    int dimensions();
}
