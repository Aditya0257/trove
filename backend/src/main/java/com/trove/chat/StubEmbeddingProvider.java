/*
 * ============================================================================
 *  StubEmbeddingProvider — offline, deterministic embeddings for dev/tests
 * ============================================================================
 *  Purpose:        produce a valid, repeatable vector for a piece of text with NO
 *                  network call, so the whole pipeline runs without a cloud account.
 *  Business use:    lets the RAG feature build and run end-to-end locally (MinIO-style
 *                  parity), exactly like the stub extraction provider.
 *  Design:         seeds a PRNG from the text's hash and emits a normalised vector.
 *                  It is NOT semantically meaningful — retrieval quality only matters
 *                  in prod, where the Cloudflare provider is used.
 * ============================================================================
 */
package com.trove.chat;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;

@Component
public class StubEmbeddingProvider implements EmbeddingProvider {

    private final int dims;

    public StubEmbeddingProvider(ChatProperties props) {
        this.dims = props.getDimensions();
    }

    @Override
    public float[] embed(String text, UUID billToUserId) {
        long seed = 1125899906842597L;   // deterministic per text
        for (byte b : (text == null ? "" : text).getBytes(StandardCharsets.UTF_8)) {
            seed = 31 * seed + b;
        }
        Random rnd = new Random(seed);
        float[] v = new float[dims];
        double norm = 0;
        for (int i = 0; i < dims; i++) {
            v[i] = (float) rnd.nextGaussian();
            norm += (double) v[i] * v[i];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dims; i++) {
                v[i] /= (float) norm;
            }
        }
        return v;
    }

    @Override
    public String model() {
        return "stub-embed";
    }

    @Override
    public int dimensions() {
        return dims;
    }
}
