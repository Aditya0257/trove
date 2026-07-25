/// ============================================================================
///  SpendScreen - a glance at spend for a space
/// ============================================================================
///  Purpose:  show the total and a per-category breakdown (simple bars) over the
///            space's confirmed documents. Pull to refresh. Read-only.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/models/spend.dart';
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
          error: (_, __) => ListView(children: [
            const SizedBox(height: 80),
            Center(
              child: Text("Couldn't load spend. Pull to retry.",
                  style: TextStyle(color: scheme.onSurfaceVariant),),
            ),
          ],),
          data: (s) => _body(context, s, scheme),
        ),
      ),
    );
  }

  Widget _body(BuildContext context, SpendSummary s, ColorScheme scheme) {
    final money = NumberFormat.currency(symbol: '${s.currency} ', decimalDigits: 2);
    if (s.count == 0) {
      return ListView(children: [
        const SizedBox(height: 80),
        Center(
          child: Text('No confirmed spend yet.',
              style: TextStyle(color: scheme.onSurfaceVariant),),
        ),
      ],);
    }
    final maxTotal = s.byCategory.fold<double>(
        0, (m, c) => c.total > m ? c.total : m,);
    final sorted = [...s.byCategory]..sort((a, b) => b.total.compareTo(a.total));

    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        Text('Total spend', style: TextStyle(color: scheme.onSurfaceVariant)),
        const SizedBox(height: 4),
        Text(money.format(s.total),
            style: Theme.of(context)
                .textTheme
                .headlineMedium
                ?.copyWith(fontWeight: FontWeight.w800),),
        Text('${s.count} confirmed document${s.count == 1 ? '' : 's'}',
            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),),
        const SizedBox(height: 24),
        Text('By category', style: TextStyle(color: scheme.onSurfaceVariant)),
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
                    Text(money.format(c.total),
                        style: const TextStyle(fontWeight: FontWeight.w600),),
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
