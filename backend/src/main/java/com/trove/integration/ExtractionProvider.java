/*
 * ============================================================================
 *  ExtractionProvider — the pluggable "read a document" interface (load-bearing)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  One method: given a file's bytes + mime type, return structured fields. This is
 *  the seam that lets us start with a stub and later drop in a real vision model
 *  with no other code change.
 *
 *  Business use case
 *  -----------------
 *  "Snap a bill and Trove figures out what it is" is the product's magic. Isolating
 *  it behind an interface means we can ship the whole pipeline now (stub) and
 *  upgrade the intelligence later without touching upload/storage/review.
 *
 *  Solution architecture
 *  ---------------------
 *  The second load-bearing interface (DESIGN.md §6.2). Impls are Spring beans keyed
 *  by name ("stub", later "vision"); the active one is chosen by
 *  trove.extraction.provider. See DECISIONS.md → D3 for how results are applied
 *  asynchronously and reliably.
 *
 *  Design
 *  ------
 *  Signature copied from DESIGN.md §6.2. Bytes + mime in, ExtractionResult out.
 *  Providers must be side-effect free — they only read; persistence is the worker's job.
 *
 *  Reasoning & logic
 *  -----------------
 *  Taking raw bytes (not a MultipartFile) keeps providers usable from any source —
 *  an upload, a reconciler re-read from storage, or a forwarded email later.
 *
 *  The model/effort-aware overload (added for the fallback chain, DECISIONS.md → D9)
 *  is a default method delegating to the documented 2-arg call, so existing/simple
 *  providers (e.g. the stub) need not change, while model-selecting providers
 *  (Gemini, Ollama) override it.
 * ============================================================================
 */
package com.trove.integration;
import com.trove.dto.ExtractionRequest;
import com.trove.dto.ExtractionResult;

public interface ExtractionProvider {

    /** Reads the given file and returns structured fields. Pure/read-only. */
    ExtractionResult extract(byte[] fileBytes, String mimeType);

    /**
     * Reads the file using a specific model/effort (chosen per chain step). Default
     * ignores the request and delegates to {@link #extract(byte[], String)}; providers
     * that support model selection override this. May throw {@link ExtractionException}.
     */
    default ExtractionResult extract(byte[] fileBytes, String mimeType, ExtractionRequest request) {
        return extract(fileBytes, mimeType);
    }
}
