/*
 * ============================================================================
 *  ExtractionOutcome — the engine's result plus which step produced it
 * ============================================================================
 *  Purpose:        pairs the ExtractionResult with the provider + model that
 *                  actually produced it, and whether it passed the acceptance gate.
 *  Business use:    provenance — the worker records provider/model on the document
 *                  so you can see the fallback chain at work and debug switches.
 *  Design:         returned by ExtractionEngine.run(...). See DECISIONS.md → D9.
 * ============================================================================
 */
package com.trove.extraction.engine;

import com.trove.extraction.ExtractionResult;

public record ExtractionOutcome(
        ExtractionResult result,
        String provider,
        String model,
        boolean accepted
) {
}
