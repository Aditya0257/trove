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
 * ============================================================================
 */
package com.trove.extraction;

public interface ExtractionProvider {

    /** Reads the given file and returns structured fields. Pure/read-only. */
    ExtractionResult extract(byte[] fileBytes, String mimeType);
}
