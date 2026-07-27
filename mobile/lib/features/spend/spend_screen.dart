/// ============================================================================
///  SpendScreen - a glance at spend for a space
/// ============================================================================
///  Purpose:  show the total plus two views over the space's confirmed
///            documents, and let the reader pick how each is drawn. Read-only,
///            pull to refresh.
///
///  Layout:   totals -> per-category breakdown (chart + numeric list) FIRST,
///            then the by-time series below it.
///
///  Toggles:
///    - Category view: Bar (per-category vertical bars) | Donut (a CustomPaint
///      donut with a label / amount / swatch legend).
///    - Time view:     Bar (per-period bars) | Wave (a smooth CustomPaint area
///      line of the same series).
///      The Category and Time view choices are persisted via chartPrefsProvider
///      (the keychain), so they survive reload and re-login; switching them never
///      refetches.
///    - Granularity:   Day | Week | Month for the time series. This one DOES
///      refetch: it keys spendByMonthProvider by (spaceId, granularity), so the
///      backend re-runs GET /api/spend/by-month with the chosen granularity.
/// ============================================================================
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/chart_prefs_controller.dart';
import '../../core/models/spend.dart';
import '../../ui/widgets/help_card.dart';
import 'spend_api.dart';

/// A fixed palette for donut slices / legend swatches. Slices are coloured by
/// position (cycling), so the same category keeps its colour within one chart
/// and the legend matches the ring.
const List<Color> _slicePalette = [
  Color(0xFF4F46E5),
  Color(0xFF0EA5E9),
  Color(0xFF10B981),
  Color(0xFFF59E0B),
  Color(0xFFEF4444),
  Color(0xFFEC4899),
  Color(0xFF8B5CF6),
  Color(0xFF14B8A6),
];

Color _sliceColour(int index) => _slicePalette[index % _slicePalette.length];

class SpendScreen extends ConsumerStatefulWidget {
  const SpendScreen({super.key, required this.spaceId});
  final String spaceId;

  @override
  ConsumerState<SpendScreen> createState() => _SpendScreenState();
}

class _SpendScreenState extends ConsumerState<SpendScreen> {
  /// Time-series granularity. One of 'day' | 'week' | 'month'. Changing it
  /// refetches the series (it is part of the provider key). Defaults to month.
  String _granularity = 'month';

  SpendSeriesKey get _seriesKey =>
      (spaceId: widget.spaceId, granularity: _granularity);

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final summary = ref.watch(spendSummaryProvider(widget.spaceId));
    final byMonth = ref.watch(spendByMonthProvider(_seriesKey));
    // Watched here so a change to a remembered chart view rebuilds _body below.
    final chart = ref.watch(chartPrefsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Spend')),
      body: RefreshIndicator(
        onRefresh: () async {
          await Future.wait([
            ref.refresh(spendSummaryProvider(widget.spaceId).future),
            ref.refresh(spendByMonthProvider(_seriesKey).future),
          ]);
        },
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
          data: (s) => _body(context, s, byMonth, scheme, chart),
        ),
      ),
    );
  }

  Widget _body(
    BuildContext context,
    SpendSummary s,
    AsyncValue<List<MonthlySpend>> byMonth,
    ColorScheme scheme,
    ChartPrefs chart,
  ) {
    final money =
        NumberFormat.currency(symbol: '${s.currency} ', decimalDigits: 2);

    const help = HelpCard(
      title: 'How spend is tracked',
      user: 'Only confirmed documents count toward spend. Anything still in '
          'review is left out until you confirm its amount, so the totals and '
          'charts only ever reflect figures you have checked. Switch each chart '
          'between views, pick a Day / Week / Month grouping for the time '
          'series, and pull down to refresh. Your chart-view choices are '
          'remembered for next time.',
      dev: 'Reads GET /api/spend/summary (confirmed documents only) for the '
          'total and per-category breakdown, and GET /api/spend/by-month for '
          'the time series. The granularity toggle (day | week | month) is part '
          'of the by-month provider key, so changing it refetches; the Bar / '
          'Donut and Bar / Wave choices are held in chartPrefsProvider and '
          'persisted to the keychain, so they survive reloads and re-login.',
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
    final catBars = [
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

        // ---- Per-category breakdown FIRST (chart + numeric list). ----
        _sectionHeader(
          'Spend by category',
          scheme,
          trailing: _boolToggle(
            selected: chart.catDonut,
            offLabel: 'Bar',
            onLabel: 'Donut',
            onChanged: (v) => ref.read(chartPrefsProvider.notifier).setCatDonut(v),
          ),
        ),
        const SizedBox(height: 12),
        Card(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 16, 12, 12),
            child: chart.catDonut
                ? _DonutChart(categories: sorted, money: money)
                : _BarChart(bars: catBars, money: money),
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
        const SizedBox(height: 24),

        // ---- By-time series SECOND. ----
        _sectionHeader(
          'Spend over time',
          scheme,
          trailing: _boolToggle(
            selected: chart.timeWave,
            offLabel: 'Bar',
            onLabel: 'Wave',
            onChanged: (v) => ref.read(chartPrefsProvider.notifier).setTimeWave(v),
          ),
        ),
        const SizedBox(height: 12),
        SegmentedButton<String>(
          showSelectedIcon: false,
          style: SegmentedButton.styleFrom(
            visualDensity: VisualDensity.compact,
          ),
          segments: const [
            ButtonSegment(value: 'day', label: Text('Day')),
            ButtonSegment(value: 'week', label: Text('Week')),
            ButtonSegment(value: 'month', label: Text('Month')),
          ],
          selected: {_granularity},
          onSelectionChanged: (sel) =>
              setState(() => _granularity = sel.first),
        ),
        const SizedBox(height: 12),
        _timeCard(context, byMonth, money, scheme, chart.timeWave),
      ],
    );
  }

  /// A section title with an optional trailing toggle on the same row.
  Widget _sectionHeader(String title, ColorScheme scheme, {Widget? trailing}) {
    return Row(
      children: [
        Expanded(
          child: Text(title, style: TextStyle(color: scheme.onSurfaceVariant)),
        ),
        if (trailing != null) trailing,
      ],
    );
  }

  /// A compact two-option SegmentedButton used for the chart-type toggles.
  Widget _boolToggle({
    required bool selected,
    required String offLabel,
    required String onLabel,
    required ValueChanged<bool> onChanged,
  }) {
    return SegmentedButton<bool>(
      showSelectedIcon: false,
      style: SegmentedButton.styleFrom(
        visualDensity: VisualDensity.compact,
      ),
      segments: [
        ButtonSegment(value: false, label: Text(offLabel)),
        ButtonSegment(value: true, label: Text(onLabel)),
      ],
      selected: {selected},
      onSelectionChanged: (sel) => onChanged(sel.first),
    );
  }

  /// The time-series card: draws the same series as bars or as a wave. Handles
  /// its own loading / error / empty states so a slow or failed series never
  /// blocks the (already loaded) totals and category breakdown above it.
  Widget _timeCard(
    BuildContext context,
    AsyncValue<List<MonthlySpend>> byMonth,
    NumberFormat money,
    ColorScheme scheme,
    bool timeWave,
  ) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 16, 12, 12),
        child: byMonth.when(
          loading: () => const Center(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: CircularProgressIndicator(),
            ),
          ),
          error: (_, __) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Text(
                "Couldn't load the time series. Pull to retry.",
                style: TextStyle(color: scheme.onSurfaceVariant),
              ),
            ),
          ),
          data: (periods) {
            if (periods.isEmpty) {
              return Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text(
                    'No spend in this period yet.',
                    style: TextStyle(color: scheme.onSurfaceVariant),
                  ),
                ),
              );
            }
            // Chronological order, oldest to newest (newest on the right).
            final ordered = [...periods]
              ..sort((a, b) => a.period.compareTo(b.period));
            final bars = [
              for (final m in ordered) _Bar(label: m.period, value: m.total),
            ];
            return timeWave
                ? _WaveChart(bars: bars, money: money)
                : _BarChart(bars: bars, money: money);
          },
        ),
      ),
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
                            ? (plotHeight * (b.value / maxValue))
                                .clamp(4.0, plotHeight)
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

/// A smooth area/line chart of the same _Bar series, drawn with CustomPaint.
/// Points sit at the centre of equal slots so they line up with the period
/// labels rendered in a Row beneath the plot.
class _WaveChart extends StatelessWidget {
  const _WaveChart({required this.bars, required this.money});

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
      // plot + gap + TWO label lines (compact value and period) beneath it. The old
      // +30 clipped that second line and threw a few-pixel overflow; +52 fits both.
      height: plotHeight + 52,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            height: plotHeight,
            child: Tooltip(
              message: [
                for (final b in bars) '${b.label}: ${money.format(b.value)}',
              ].join('\n'),
              child: CustomPaint(
                painter: _WavePainter(
                  values: [for (final b in bars) b.value],
                  maxValue: maxValue,
                  color: scheme.primary,
                ),
                child: const SizedBox.expand(),
              ),
            ),
          ),
          const SizedBox(height: 6),
          Row(
            children: [
              for (final b in bars)
                Expanded(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        compact.format(b.value),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: scheme.onSurfaceVariant,
                          fontSize: 10,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
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
            ],
          ),
        ],
      ),
    );
  }
}

class _WavePainter extends CustomPainter {
  const _WavePainter({
    required this.values,
    required this.maxValue,
    required this.color,
  });

  final List<double> values;
  final double maxValue;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    if (values.isEmpty) return;

    const topPad = 8.0;
    const bottomPad = 2.0;
    final usableH = size.height - topPad - bottomPad;
    final n = values.length;
    final slot = size.width / n;

    double xAt(int i) => slot * (i + 0.5);
    double yAt(double v) {
      final frac = maxValue > 0 ? (v / maxValue) : 0.0;
      return topPad + usableH * (1 - frac);
    }

    final points = [
      for (var i = 0; i < n; i++) Offset(xAt(i), yAt(values[i])),
    ];

    // Smooth-ish line: cubic segments with horizontal tangents at each point.
    final line = Path()..moveTo(points.first.dx, points.first.dy);
    for (var i = 1; i < n; i++) {
      final prev = points[i - 1];
      final cur = points[i];
      final cx = (prev.dx + cur.dx) / 2;
      line.cubicTo(cx, prev.dy, cx, cur.dy, cur.dx, cur.dy);
    }

    // Fill under the curve.
    final fill = Path.from(line)
      ..lineTo(points.last.dx, size.height)
      ..lineTo(points.first.dx, size.height)
      ..close();
    canvas.drawPath(
      fill,
      Paint()
        ..style = PaintingStyle.fill
        ..color = color.withValues(alpha: 0.14),
    );

    // The line itself.
    canvas.drawPath(
      line,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.5
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round
        ..color = color,
    );

    // A dot at each data point.
    final dot = Paint()
      ..style = PaintingStyle.fill
      ..color = color;
    for (final p in points) {
      canvas.drawCircle(p, 3, dot);
    }
  }

  @override
  bool shouldRepaint(_WavePainter old) =>
      old.values != values || old.maxValue != maxValue || old.color != color;
}

/// A donut/pie of the per-category totals with a simple legend (swatch + label
/// + amount). Slice colours cycle through [_slicePalette].
class _DonutChart extends StatelessWidget {
  const _DonutChart({required this.categories, required this.money});

  final List<CategorySpend> categories;
  final NumberFormat money;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final total = categories.fold<double>(0, (m, c) => m + c.total);

    return Column(
      children: [
        SizedBox(
          height: 180,
          child: Center(
            child: SizedBox(
              width: 180,
              height: 180,
              child: CustomPaint(
                painter: _DonutPainter(
                  values: [for (final c in categories) c.total],
                  total: total,
                  ringColor: scheme.surfaceContainerHighest,
                ),
              ),
            ),
          ),
        ),
        const SizedBox(height: 16),
        for (var i = 0; i < categories.length; i++)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(
              children: [
                Container(
                  width: 14,
                  height: 14,
                  decoration: BoxDecoration(
                    color: _sliceColour(i),
                    borderRadius: BorderRadius.circular(3),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    categories[i].label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 8),
                Text(
                  money.format(categories[i].total),
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

class _DonutPainter extends CustomPainter {
  const _DonutPainter({
    required this.values,
    required this.total,
    required this.ringColor,
  });

  final List<double> values;
  final double total;
  final Color ringColor;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final stroke = size.shortestSide * 0.28;
    final radius = size.shortestSide / 2 - stroke / 2;
    final rect = Rect.fromCircle(center: center, radius: radius);

    final base = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..color = ringColor;

    if (total <= 0) {
      canvas.drawArc(rect, 0, math.pi * 2, false, base);
      return;
    }

    // Faint full ring underneath, then the coloured slices on top.
    canvas.drawArc(rect, 0, math.pi * 2, false, base);

    var start = -math.pi / 2;
    const gap = 0.02; // small gap between slices, in radians
    for (var i = 0; i < values.length; i++) {
      final sweep = (values[i] / total) * math.pi * 2;
      if (sweep <= 0) continue;
      final slice = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke
        ..strokeCap = StrokeCap.butt
        ..color = _sliceColour(i);
      final drawSweep = (sweep - gap).clamp(0.0, math.pi * 2);
      canvas.drawArc(rect, start + gap / 2, drawSweep, false, slice);
      start += sweep;
    }
  }

  @override
  bool shouldRepaint(_DonutPainter old) =>
      old.values != values ||
      old.total != total ||
      old.ringColor != ringColor;
}
