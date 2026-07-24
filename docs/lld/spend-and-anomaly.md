# LLD: Spend and Anomaly

Modules: `analytics` (spend), `anomaly`. Both operate only over confirmed documents, because
extracted numbers are not trusted until a human confirms them (D11).

## 1. Spend (analytics)

| Class | Role |
| --- | --- |
| `AnalyticsController` | `GET /api/spend/by-category`, `/by-month`, `/summary`. |
| `AnalyticsService` | Aggregation logic over confirmed documents. |
| `AnalyticsRepository` | The grouped sum queries. |
| `MonthlySpend` | The by-period result shape. |

`by-category` sums amounts grouped by category over a date range; `by-month` groups by period
with a day, week or month granularity; `summary` returns the combined view (total, per-category
breakdown, currency, and a rates-as-of marker for any conversion). All are space-scoped and cover
only `confirmed`, non-deleted documents, so pending or trashed documents never affect totals. The
web Spend screen renders these as bar and donut breakdowns and a bar or wave time series.

## 2. Anomaly

The "your electricity is higher than usual" feature (D13). It is deliberately arithmetic, not a
model call: cheap, explainable, and spends no AI budget.

| Class | Role |
| --- | --- |
| `AnomalyService` | Compare a document's amount to the trailing category average; produce a verdict. |
| `AnomalyResult` | The verdict record; `toMap()` is stored in `document.extra.anomaly`. |
| `AnomalyProperties` | Threshold, lookback window, minimum samples. |
| `AnomalyController` | `GET /api/anomalies` lists flagged documents. |
| `AnomalyRepository` | The trailing-amounts query. |

### How it decides

At confirm time, `AnomalyService.evaluate` pulls the amounts of prior confirmed documents in the
same category within the lookback window (12 months by default), excluding the document being
confirmed. If there are fewer than the minimum samples (3 by default) it reports "not enough
history" and never flags, so a first bill never false-alarms. Otherwise it computes the trailing
average and the percentage over; it flags when the amount exceeds the average by the threshold
fraction (40% by default). It flags high only, matching the stated use case.

The full verdict (whether anomalous, the average, the delta percentage, the sample count, the
threshold) is written to `document.extra.anomaly` during confirm, so every surface reads a stored
value rather than recomputing:

- the review screen shows "about 42% higher than usual (you normally pay around a certain amount)";
- the Documents list shows a small "higher than usual" marker with the percentage;
- the Spend screen lists flagged documents under "Flagged as unusual".

### Why store the verdict rather than recompute

Storing it at confirm keeps every read cheap and consistent, and means the verdict reflects the
history as it stood when the document was confirmed. The trade-off is that it does not
retroactively re-evaluate old documents when new history arrives, which is acceptable: an anomaly
is a point-in-time judgement about a bill when it was filed.

## 3. Configuration

`trove.anomaly.threshold-pct` (0.40), `lookback-months` (12), `min-samples` (3). See
[../operations/configuration.md](../operations/configuration.md).
