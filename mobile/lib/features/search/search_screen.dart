/// ============================================================================
///  SearchScreen — natural-language search with lively progress
/// ============================================================================
///
///  Purpose
///  -------
///  Free-text search ("my last water bill", "most expensive shopping") over a space
///  via `GET /api/search`. Shows the server's interpretation as chips and the results.
///
///  Design
///  ------
///  While a query is in flight the button cycles through friendly progress lines
///  (interactivity the owner asked for) — the request itself may hit the LLM parser,
///  so a little life keeps it from feeling stuck.
/// ============================================================================
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/models/document.dart';
import '../../core/providers.dart';
import '../../ui/widgets/help_card.dart';

class SearchScreen extends ConsumerStatefulWidget {
  const SearchScreen({super.key, required this.spaceId});
  final String spaceId;

  @override
  ConsumerState<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends ConsumerState<SearchScreen> {
  static const _phrases = [
    'Searching your vault…',
    'Reading the fine print…',
    'Matching merchants and amounts…',
    'Sorting the results…',
  ];

  static const _examples = [
    'my last water bill',
    'most expensive shopping',
    'all Nike purchases',
  ];

  final _q = TextEditingController();
  bool _busy = false;
  Timer? _ticker;
  int _phrase = 0;
  Map<String, dynamic>? _interpreted;
  List<TroveDocument>? _results;

  @override
  void dispose() {
    _q.dispose();
    _ticker?.cancel();
    super.dispose();
  }

  void _startTicker() {
    _phrase = 0;
    _ticker?.cancel();
    _ticker = Timer.periodic(const Duration(milliseconds: 900), (_) {
      if (mounted) setState(() => _phrase = (_phrase + 1) % _phrases.length);
    });
  }

  Future<void> _search() async {
    final q = _q.text.trim();
    if (q.isEmpty) return;
    setState(() => _busy = true);
    _startTicker();
    try {
      final data = await ref.read(apiClientProvider).get(
        '/api/search',
        query: {'q': q, 'spaceId': widget.spaceId},
      ) as Map<String, dynamic>;
      final results = (data['results'] as List<dynamic>? ?? [])
          .map((e) => TroveDocument.fromJson((e as Map).cast<String, dynamic>()))
          .toList();
      setState(() {
        _interpreted = (data['interpreted'] as Map?)?.cast<String, dynamic>();
        _results = results;
      });
    } catch (_) {
      // toast shown by the client
    } finally {
      _ticker?.cancel();
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Search')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const HelpCard(
              title: 'Searching your vault',
              user:
                  "Search in plain language, like 'my last water bill' or 'all Nike purchases'. It understands the intent, not just keywords, and falls back to a keyword search if the daily AI budget is used up.",
              dev: null,
            ),
            TextField(
              controller: _q,
              autofocus: true,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _busy ? null : _search(),
              decoration: InputDecoration(
                hintText: 'e.g. "most expensive shopping" or "last water bill"',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: _busy
                    ? const Padding(
                        padding: EdgeInsets.all(12),
                        child: SizedBox(
                            height: 18, width: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),),
                      )
                    : IconButton(icon: const Icon(Icons.arrow_forward), onPressed: _search),
              ),
            ),
            if (_busy)
              Padding(
                padding: const EdgeInsets.only(top: 14),
                child: Row(
                  children: [
                    const SizedBox(
                        height: 14, width: 14,
                        child: CircularProgressIndicator(strokeWidth: 2),),
                    const SizedBox(width: 10),
                    AnimatedSwitcher(
                      duration: const Duration(milliseconds: 300),
                      child: Text(_phrases[_phrase],
                          key: ValueKey(_phrase),
                          style: TextStyle(color: scheme.onSurfaceVariant),),
                    ),
                  ],
                ),
              ),
            if (!_busy && _interpreted != null) _interpretedChips(scheme),
            if (!_busy && _results == null) _exampleChips(),
            const SizedBox(height: 8),
            Expanded(child: _resultsView(scheme)),
          ],
        ),
      ),
    );
  }

  Widget _exampleChips() => Padding(
        padding: const EdgeInsets.only(top: 14),
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final ex in _examples)
              ActionChip(
                label: Text(ex),
                onPressed: () {
                  _q.text = ex;
                  _search();
                },
              ),
          ],
        ),
      );

  Widget _interpretedChips(ColorScheme scheme) {
    final i = _interpreted!;
    final chips = <String>[
      if (i['categoryCode'] != null) 'category: ${i['categoryCode']}',
      if (i['text'] != null) 'text: ${i['text']}',
      if (i['amountMin'] != null) '≥ ${i['amountMin']}',
      if (i['amountMax'] != null) '≤ ${i['amountMax']}',
      if (i['sortBy'] != null) 'sort: ${i['sortBy']} ${i['sortDir'] ?? ''}'.trim(),
    ];
    if (chips.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Wrap(
        spacing: 6,
        runSpacing: 6,
        children: [for (final c in chips) Chip(label: Text(c), visualDensity: VisualDensity.compact)],
      ),
    );
  }

  Widget _resultsView(ColorScheme scheme) {
    if (_results == null) {
      return Center(
        child: Text('Search your documents in plain English.',
            style: TextStyle(color: scheme.onSurfaceVariant),),
      );
    }
    if (_results!.isEmpty) {
      return Center(
        child: Text('Nothing matched that.',
            style: TextStyle(color: scheme.onSurfaceVariant),),
      );
    }
    return ListView.separated(
      itemCount: _results!.length,
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (_, idx) {
        final d = _results![idx];
        final amount = d.amount != null
            ? '${d.currency ?? ''} ${d.amount!.toStringAsFixed(2)}'.trim()
            : null;
        return ListTile(
          title: Text(d.merchant ?? d.category ?? 'Document'),
          subtitle: Text([
            d.category,
            d.docDate?.toIso8601String().substring(0, 10),
          ].whereType<String>().join(' · '),),
          trailing: amount != null
              ? Text(amount, style: const TextStyle(fontWeight: FontWeight.w600))
              : null,
          onTap: () => context.push('/document', extra: d),
        );
      },
    );
  }
}
