/// ============================================================================
///  InsightsScreen - document intelligence: expiring soon + recurring
/// ============================================================================
///  Purpose:  one glance at what needs action (bills due, renewals, warranties
///            ending) and what recurs (subscriptions), computed from confirmed
///            documents. Read-only, pull to refresh.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/models/insights.dart';
import '../../ui/widgets/help_card.dart';
import '../documents/documents_api.dart';
import 'insights_api.dart';

class InsightsScreen extends ConsumerStatefulWidget {
  const InsightsScreen({super.key, required this.spaceId});
  final String spaceId;

  @override
  ConsumerState<InsightsScreen> createState() => _InsightsScreenState();
}

class _InsightsScreenState extends ConsumerState<InsightsScreen> {
  int _windowDays = 90;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final expiring = ref.watch(
      expiringProvider((spaceId: widget.spaceId, withinDays: _windowDays)),
    );
    final recurring = ref.watch(recurringProvider(widget.spaceId));

    return Scaffold(
      appBar: AppBar(title: const Text('Insights')),
      body: RefreshIndicator(
        onRefresh: () async {
          await Future.wait([
            ref.refresh(expiringProvider(
              (spaceId: widget.spaceId, withinDays: _windowDays),
            ).future,),
            ref.refresh(recurringProvider(widget.spaceId).future),
          ]);
        },
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const HelpCard(
              title: 'What Insights shows',
              user:
                  'Everything coming up in one place - bills due, insurance or subscription renewals, and '
                  'warranties about to run out (plus anything that lapsed in the last month, so a just-expired '
                  'ID is not hidden). Below that, the merchants that bill you on a regular rhythm, with the next '
                  'expected date. It is all worked out from documents you have confirmed, so it stays current.',
              dev: null,
            ),
            _sectionHeader(scheme, 'Expiring soon'),
            const SizedBox(height: 8),
            _windowToggle(),
            const SizedBox(height: 12),
            expiring.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(vertical: 28),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (_, __) => _errorLine(scheme, "Couldn't load. Pull to retry."),
              data: (items) => items.isEmpty
                  ? _emptyLine(scheme, 'Nothing coming up in this window. You are all clear.')
                  : Column(children: [for (final e in items) _ExpiringTile(item: e)]),
            ),
            const SizedBox(height: 24),
            _sectionHeader(scheme, 'Recurring & subscriptions'),
            const SizedBox(height: 8),
            recurring.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(vertical: 28),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (_, __) => _errorLine(scheme, "Couldn't load. Pull to retry."),
              data: (groups) => groups.isEmpty
                  ? _emptyLine(scheme,
                      'No recurring patterns yet. They appear once a merchant has billed you a few times on a steady rhythm.',)
                  : Column(children: [for (final g in groups) _RecurringTile(group: g)]),
            ),
          ],
        ),
      ),
    );
  }

  Widget _sectionHeader(ColorScheme scheme, String text) => Text(
        text,
        style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 18),
      );

  Widget _windowToggle() => SegmentedButton<int>(
        showSelectedIcon: false,
        style: SegmentedButton.styleFrom(visualDensity: VisualDensity.compact),
        segments: const [
          ButtonSegment(value: 30, label: Text('30d')),
          ButtonSegment(value: 90, label: Text('90d')),
          ButtonSegment(value: 180, label: Text('6mo')),
          ButtonSegment(value: 365, label: Text('1yr')),
        ],
        selected: {_windowDays},
        onSelectionChanged: (s) => setState(() => _windowDays = s.first),
      );

  Widget _emptyLine(ColorScheme scheme, String text) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Text(text, style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13)),
      );

  Widget _errorLine(ColorScheme scheme, String text) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Text(text, style: TextStyle(color: scheme.onSurfaceVariant)),
      );
}

/// Human phrase for a signed days-left value.
String _rel(int daysLeft) {
  if (daysLeft < 0) {
    final n = -daysLeft;
    return 'overdue by $n day${n == 1 ? '' : 's'}';
  }
  if (daysLeft == 0) return 'today';
  if (daysLeft == 1) return 'tomorrow';
  return 'in $daysLeft days';
}

String _kindLabel(String kind) =>
    kind == 'due' ? 'Due' : kind == 'renewal' ? 'Renewal' : 'Warranty';

String _cadenceLabel(String c) => c.isEmpty ? c : '${c[0].toUpperCase()}${c.substring(1)}';

class _ExpiringTile extends ConsumerWidget {
  const _ExpiringTile({required this.item});
  final ExpiringItem item;

  Future<void> _open(BuildContext context, WidgetRef ref) async {
    try {
      final doc = await ref.read(documentsApiProvider).get(item.documentId);
      if (context.mounted) context.push('/document', extra: doc);
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final overdue = item.daysLeft < 0;
    final soon = !overdue && item.daysLeft <= 7;
    final money = NumberFormat.currency(symbol: '${item.currency ?? 'INR'} ', decimalDigits: 2);
    final relColor = overdue ? scheme.error : (soon ? scheme.primary : scheme.onSurfaceVariant);
    return Card(
      color: overdue ? scheme.errorContainer.withValues(alpha: 0.35) : null,
      child: ListTile(
        onTap: () => _open(context, ref),
        title: Row(
          children: [
            _kindChip(scheme, item.kind),
            const SizedBox(width: 8),
            Expanded(
              child: Text(item.title,
                  maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w600),),
            ),
          ],
        ),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Row(
            children: [
              Text(DateFormat('d MMM yyyy').format(item.date),
                  style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12.5),),
              const SizedBox(width: 8),
              Text(_rel(item.daysLeft),
                  style: TextStyle(color: relColor, fontSize: 12.5, fontWeight: FontWeight.w600),),
            ],
          ),
        ),
        trailing: item.amount != null
            ? Text(money.format(item.amount),
                style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),)
            : null,
      ),
    );
  }

  Widget _kindChip(ColorScheme scheme, String kind) {
    final (bg, fg) = switch (kind) {
      'renewal' => (scheme.primaryContainer, scheme.onPrimaryContainer),
      'warranty' => (scheme.surfaceContainerHighest, scheme.onSurfaceVariant),
      _ => (scheme.secondaryContainer, scheme.onSecondaryContainer),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(6)),
      child: Text(_kindLabel(kind),
          style: TextStyle(color: fg, fontSize: 10, fontWeight: FontWeight.w800),),
    );
  }
}

class _RecurringTile extends StatelessWidget {
  const _RecurringTile({required this.group});
  final RecurringGroup group;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final money = NumberFormat.currency(symbol: '${group.currency ?? 'INR'} ', decimalDigits: 2);
    final parts = <String>[
      _cadenceLabel(group.cadence),
      if (group.averageAmount != null) 'about ${money.format(group.averageAmount)}',
      '${group.occurrences}x seen',
    ];
    return Card(
      child: ListTile(
        leading: Icon(Icons.autorenew, color: scheme.primary),
        title: Text(group.merchant ?? '(unknown merchant)',
            style: const TextStyle(fontWeight: FontWeight.w600),),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(
            '${group.categoryLabel ?? group.category ?? ''}\n${parts.join(' - ')}',
            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12.5, height: 1.4),
          ),
        ),
        isThreeLine: true,
        trailing: group.nextExpected != null
            ? Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text('next', style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 10)),
                  Text(DateFormat('d MMM').format(group.nextExpected!),
                      style: TextStyle(color: scheme.primary, fontSize: 13, fontWeight: FontWeight.w700),),
                ],
              )
            : null,
      ),
    );
  }
}
