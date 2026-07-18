/*
 * ============================================================================
 *  AnomalyProperties — thresholds for "higher than usual" detection
 * ============================================================================
 *  Purpose:        binds trove.anomaly.* (deviation threshold, lookback window,
 *                  minimum sample size).
 *  Business use:    controls when a bill is flagged as unusually high, e.g. "40%
 *                  above the trailing average for this category."
 *  Design:         min-samples guards against flagging on too little history (one
 *                  or two past bills isn't a reliable baseline).
 * ============================================================================
 */
package com.trove.anomaly;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.anomaly")
public class AnomalyProperties {

    /** Flag when amount exceeds the trailing average by at least this fraction (0.40 = 40%). */
    private double thresholdPct = 0.40;

    /** Months of history to average over. */
    private int lookbackMonths = 12;

    /** Minimum prior samples required before we judge (else "not enough history"). */
    private int minSamples = 3;

    public double getThresholdPct() { return thresholdPct; }
    public void setThresholdPct(double thresholdPct) { this.thresholdPct = thresholdPct; }

    public int getLookbackMonths() { return lookbackMonths; }
    public void setLookbackMonths(int lookbackMonths) { this.lookbackMonths = lookbackMonths; }

    public int getMinSamples() { return minSamples; }
    public void setMinSamples(int minSamples) { this.minSamples = minSamples; }
}
