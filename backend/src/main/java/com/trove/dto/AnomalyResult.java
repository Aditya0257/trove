/*
 * ============================================================================
 *  AnomalyResult — outcome of comparing a bill against its trailing average
 * ============================================================================
 *  Purpose:        the verdict for one document: is it anomalous, and the numbers
 *                  behind that call (average, delta, sample size, threshold).
 *  Business use:    stored on the document (in `extra.anomaly`) and shown to the
 *                  user as "40% higher than your usual electricity bill."
 *  Design:         a plain record; `toMap()` produces the JSON stored in extra.
 * ============================================================================
 */
package com.trove.dto;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public record AnomalyResult(
        boolean anomaly,
        BigDecimal amount,
        BigDecimal average,
        BigDecimal deltaPct,   // (amount - average) / average, e.g. 0.42 = +42%
        int sampleCount,
        BigDecimal thresholdPct,
        boolean enoughHistory
) {
    /** Serializes to the map stored under document.extra["anomaly"]. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anomaly", anomaly);
        m.put("amount", amount);
        m.put("average", average);
        m.put("deltaPct", deltaPct);
        m.put("sampleCount", sampleCount);
        m.put("thresholdPct", thresholdPct);
        m.put("enoughHistory", enoughHistory);
        return m;
    }
}
