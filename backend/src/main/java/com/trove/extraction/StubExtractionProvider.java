/*
 * ============================================================================
 *  StubExtractionProvider — canned extractor so the pipeline runs with no AI
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Returns a fixed, realistic ExtractionResult so the full upload → extract →
 *  review pipeline works end-to-end before any real model or API key exists.
 *
 *  Business use case
 *  -----------------
 *  Lets us prove the plumbing (storage, sidecar, async, review) first, exactly as
 *  the brief mandates ("start with a stub provider ... before wiring a real model").
 *
 *  Solution architecture
 *  ---------------------
 *  Registered as bean "stub"; selected by trove.extraction.provider=stub. The later
 *  VisionExtractionProvider will register as "vision" and be selected the same way,
 *  with zero changes elsewhere.
 *
 *  Design
 *  ------
 *  Returns an EMPTY result — category 'uncategorized', all fields null, confidence 0.
 *  As the chain's last resort it fires only when every real provider failed, so it
 *  must NOT invent values: prefilling a fake merchant/amount would force the user to
 *  delete wrong data. Empty + uncategorized + confidence 0 signals "couldn't read —
 *  please fill it in", and the review screen shows placeholders, not junk.
 *
 *  Reasoning & logic
 *  -----------------
 *  Deterministic and reads nothing from the bytes on purpose — it is the safety net
 *  that keeps the pipeline completing (never leaves a document un-processed) while
 *  being honest that no automatic reading happened.
 * ============================================================================
 */
package com.trove.extraction;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component("stub")
public class StubExtractionProvider implements ExtractionProvider {

    @Override
    public ExtractionResult extract(byte[] fileBytes, String mimeType) {
        return new ExtractionResult(
                "uncategorized",  // don't guess a category
                null,             // merchant — user fills in
                null,             // docDate
                null,             // amount
                null,             // currency
                null,             // dueDate
                List.of(),        // no line items
                null,             // rawText — nothing was read
                Map.of("note", "Automatic reading unavailable — fields left blank for you to fill."),
                BigDecimal.ZERO   // confidence 0 → clearly "not read"
        );
    }
}
