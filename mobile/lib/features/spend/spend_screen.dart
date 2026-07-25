/// ============================================================================
///  SpendScreen - a glance at spend for a space
/// ============================================================================
///  Purpose:  show the total and a per-category breakdown over the space's
///            confirmed documents, as a vertical bar chart (the headline graph)
///            plus a compact horizontal breakdown below it. Pull to refresh.
///            Read-only.
///
///  Note:     the /api/spend/summary payload (SpendSummary) carries only a
///            per-category breakdown - currency, total, count, byCategory - and
///            no monthly time series, so the bar chart plots one bar per
///            category rather than per month. If a monthly series is added to
///            the model later, feed it into the same _BarChart widget.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/models/spend.dart';
import '../../ui/widgets/help_card.dart';
import 'spend_api.dart';

class SpendScreen extends ConsumerWidget {
  const SpendScreen({super.key, required this.spaceId});
  final String spaceId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final summary = ref.watch(spendSummaryProvider(spaceId));

    return Scaffold(
      appBar: AppBar(title: const Text('Spend')),
      body: RefreshIndicator(
        onRefresh: () => ref.refresh(spendSummaryProvider(spaceId).future),
        child: summary.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, __) => ListView(
            children: [
              const SizedBox(height: 80),
              Center(
                child: Text(
                  "Couldn't load spend. Pull to retry.",
                  style: TextStyle(color: scheme.onSurfaceVariant),
                ),
              ),
            ],
          ),
          data: (s) => _body(context, s, scheme),
        ),
      ),
    );
  }

  Widget _body(BuildContext context, SpendSummary s, ColorScheme scheme) {
    final money = NumberFormat.currency(symbol: '${s.currency} ', decimalDigits: 2);

    const help = HelpCard(
      title: 'How spend is tracked',
      user: 'Only confirmed documents count toward spend. Anything still in '
          'review is left out until you confirm its amount, so the totals and '
          'chart only ever reflect figures you have checked. Pull down to '
          'refresh.',
      dev: 'Reads GET /api/spend/summary (confirmed documents only). The '
          'response is a total plus a per-category breakdown; there is no '
          'monthly series yet, so the bar chart plots one bar per category, '
          'scaled to the largest category.',
    );

    if (s.count == 0) {
      return ListView(
        padding: const EdgeInsets.all(20),
        children: [
          help,
          const SizedBox(height: 60),
          Center(
            child: Text(
              'No spending recorded yet.',
              style: TextStyle(color: scheme.onSurfaceVariant),
            ),
          ),
        ],
      );
    }

    final sorted = [...s.byCategory]..sort((a, b) => b.total.compareTo(a.total));
    final maxTotal = sorted.fold<double>(
      0,
      (m, c) => c.total > m ? c.total : m,
    );
    final bars = [
      for (final c in sorted) _Bar(label: c.label, value: c.total),
    ];

    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        help,
        Text('Total spend', style: TextStyle(color: scheme.onSurfaceVariant)),
        const SizedBox(height: 4),
        Text(
          money.format(s.total),
          style: Theme.of(context)
              .textTheme
              .headlineMedium
              ?.copyWith(fontWeight: FontWeight.w800),
        ),
        Text(
          '${s.count} confirmed document${s.count == 1 ? '' : 's'}',
          style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
        ),
        const SizedBox(height: 24),
        Text('Spend by category', style: TextStyle(color: scheme.onSurfaceVariant)),
        const SizedBox(height: 12),
        Card(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 16, 12, 12),
            child: _BarChart(bars: bars, money: money),
          ),
        ),
        const SizedBox(height: 24),
        Text('Breakdown', style: TextStyle(color: scheme.onSurfaceVariant)),
        const SizedBox(height: 8),
        for (final c in sorted)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Expanded(child: Text(c.label)),
                    Text(
                      money.format(c.total),
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                ClipRRect(
                  borderRadius: BorderRadius.circular(6),
                  child: LinearProgressIndicator(
                    value: maxTotal > 0 ? (c.total / maxTotal) : 0,
                    minHeight: 8,
                    backgroundColor: scheme.surfaceContainerHighest,
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

/// One column in the bar chart: a label under the bar and the value it plots.
class _Bar {
  const _Bar({required this.label, required this.value});
  final String label;
  final double value;
}

/// A lightweight vertical bar chart built from plain widgets (no chart package).
/// Bars are scaled against the largest value; each bar's full amount is in a
/// tooltip, with a compact amount printed above it.
class _BarChart extends StatelessWidget {
  const _BarChart({required this.bars, required this.money});

  final List<_Bar> bars;
  final NumberFormat money;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final compact = NumberFormat.compact();
    final maxValue = bars.fold<double>(
      0,
      (m, b) => b.value > m ? b.value : m,
    );
    const plotHeight = 140.0;

    return SizedBox(
      // plot area + room for the amount above and the label below each bar.
      height: plotHeight + 48,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          for (final b in bars)
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      compact.format(b.value),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: scheme.onSurfaceVariant,
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Tooltip(
                      message: '${b.label}: ${money.format(b.value)}',
                      child: Container(
                        height: maxValue > 0
                            ? (plotHeight * (b.value / maxValue)).clamp(4.0, plotHeight)
                            : 4.0,
                        decoration: BoxDecoration(
                          color: scheme.primary,
                          borderRadius: const BorderRadius.vertical(
                            top: Radius.circular(6),
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      b.label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: scheme.onSurfaceVariant,
                        fontSize: 11,
                      ),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}
