/*
 * ============================================================================
 *  AnomalyService — flags a bill that's higher than usual for its category
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Given a confirmed document's amount, category, and date, compare it to the
 *  trailing average of prior confirmed documents in the same category and decide if
 *  it's an anomaly (exceeds the average by the configured threshold).
 *
 *  Business use case
 *  -----------------
 *  "Your electricity bill is 40% higher than usual" — proactive spotting of overspend
 *  or billing errors, a headline value of the vault.
 *
 *  Solution architecture
 *  ---------------------
 *  Called synchronously during document confirm (so the result is stored in
 *  `extra.anomaly` alongside the confirmed row + sidecar). Baseline comes from
 *  AnomalyRepository over confirmed history. Compares only against CONFIRMED amounts
 *  (trusted numbers), consistent with spend tracking.
 *
 *  Reasoning & logic
 *  -----------------
 *  Needs at least min-samples of history, else it reports "not enough history" and
 *  never false-alarms on a first bill. Only flags HIGHER-than-usual (the stated use
 *  case); a symmetric low-side check can be added later.
 * ============================================================================
 */
package com.trove.anomaly;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class AnomalyService {

    private final AnomalyRepository anomalyRepository;
    private final AnomalyProperties props;

    public AnomalyService(AnomalyRepository anomalyRepository, AnomalyProperties props) {
        this.anomalyRepository = anomalyRepository;
        this.props = props;
    }

    /**
     * Evaluates whether `amount` is anomalous vs the trailing average for this
     * category. `excludeDocId` is the document being confirmed (excluded from its own
     * baseline); `asOf` is its document date (defaults to today).
     */
    @Transactional(readOnly = true)
    public AnomalyResult evaluate(UUID spaceId, UUID categoryId, BigDecimal amount,
                                  UUID excludeDocId, LocalDate asOf) {
        BigDecimal threshold = BigDecimal.valueOf(props.getThresholdPct());
        LocalDate to = asOf != null ? asOf : LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusMonths(props.getLookbackMonths());

        if (amount == null || categoryId == null) {
            return new AnomalyResult(false, amount, null, null, 0, threshold, false);
        }

        List<BigDecimal> history = anomalyRepository.priorConfirmedAmounts(
                spaceId, categoryId, excludeDocId, from, to);

        if (history.size() < props.getMinSamples()) {
            return new AnomalyResult(false, amount, null, null, history.size(), threshold, false);
        }

        BigDecimal sum = history.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = sum.divide(BigDecimal.valueOf(history.size()), 2, RoundingMode.HALF_UP);

        BigDecimal deltaPct = average.signum() == 0
                ? BigDecimal.ZERO
                : amount.subtract(average).divide(average, 4, RoundingMode.HALF_UP);

        boolean anomaly = deltaPct.compareTo(threshold) >= 0;
        return new AnomalyResult(anomaly, amount, average, deltaPct, history.size(), threshold, true);
    }
}
