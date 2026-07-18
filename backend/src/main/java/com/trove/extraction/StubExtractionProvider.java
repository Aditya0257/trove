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
 *  Values follow DESIGN.md §6.2: category 'shopping', a sample merchant, today's
 *  date, a small amount, one line item, rawText "STUB EXTRACTION", confidence 0.5.
 *  Confidence 0.5 keeps the document firmly in needs_review — a human must confirm.
 *
 *  Reasoning & logic
 *  -----------------
 *  Deterministic output makes the flow testable and reproducible. It reads nothing
 *  from the bytes on purpose — it is a placeholder for real OCR.
 * ============================================================================
 */
package com.trove.extraction;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Component("stub")
public class StubExtractionProvider implements ExtractionProvider {

    @Override
    public ExtractionResult extract(byte[] fileBytes, String mimeType) {
        return new ExtractionResult(
                "shopping",
                "Sample Store",
                LocalDate.now(ZoneOffset.UTC),
                new BigDecimal("499.00"),
                "INR",
                null,
                List.of(new LineItemDto("Sample item", new BigDecimal("1"), new BigDecimal("499.00"))),
                "STUB EXTRACTION",
                Map.of("note", "stub extraction — replace with a real provider later"),
                new BigDecimal("0.500")
        );
    }
}
