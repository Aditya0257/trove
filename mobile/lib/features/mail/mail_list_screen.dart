/// ============================================================================
///  MailListScreen - filed email threads in a space, paged
/// ============================================================================
///
///  Purpose
///  -------
///  Browse the emails that were forwarded in and auto-filed, one Card per thread.
///  Each Card leads to the thread screen (the screenshots that make up the email).
///  Pull-to-refresh re-reads the current page; a simple pager walks the rest.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../ui/widgets/help_card.dart';
import 'mail_api.dart';

class MailListScreen extends ConsumerStatefulWidget {
  const MailListScreen({super.key, required this.spaceId});
  final String spaceId;

  @override
  ConsumerState<MailListScreen> createState() => _MailListScreenState();
}

class _MailListScreenState extends ConsumerState<MailListScreen> {
  static const int _size = 25;

  int _page = 0;
  bool _busy = false;
  MailPage? _data;
  bool _failed = false;

  MailApi get _api => ref.read(mailApiProvider);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _failed = false;
    });
    try {
      final page = await _api.bundles(widget.spaceId, page: _page, size: _size);
      if (mounted) setState(() => _data = page);
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
      if (mounted) setState(() => _failed = true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  int get _pageCount {
    final total = _data?.total ?? 0;
    if (total <= 0) return 1;
    return ((total + _size - 1) ~/ _size);
  }

  Future<void> _goto(int page) async {
    if (page < 0 || page >= _pageCount || _busy) return;
    setState(() => _page = page);
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Mail')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          await context.push('/mail-compose', extra: widget.spaceId);
          if (mounted) _goto(0); // refresh to show a newly filed thread
        },
        icon: const Icon(Icons.add),
        label: const Text('File email'),
      ),
      body: Column(
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(12, 12, 12, 0),
            child: HelpCard(
              title: 'Mail',
              user:
                  "Emails you have filed, grouped into threads. Each thread can hold several screenshots of the same email. Tap a thread to view its screenshots and details.",
              dev: null,
            ),
          ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: _load,
              child: _body(scheme),
            ),
          ),
          _pager(scheme),
        ],
      ),
    );
  }

  Widget _body(ColorScheme scheme) {
    if (_busy && _data == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_failed && _data == null) {
      return ListView(
        children: [
          const SizedBox(height: 80),
          Center(
            child: Text(
              "Couldn't load your mail. Pull to retry.",
              style: TextStyle(color: scheme.onSurfaceVariant),
            ),
          ),
        ],
      );
    }
    final bundles = _data?.bundles ?? const <MailBundle>[];
    if (bundles.isEmpty) {
      return ListView(
        children: [
          const SizedBox(height: 80),
          Center(
            child: Text(
              'No emails filed yet.',
              style: TextStyle(color: scheme.onSurfaceVariant),
            ),
          ),
        ],
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.all(12),
      itemCount: bundles.length,
      itemBuilder: (_, i) => _ThreadCard(
        bundle: bundles[i],
        onTap: () => context.push('/mail-thread', extra: {
          'spaceId': widget.spaceId,
          'bundleId': bundles[i].bundleId,
        },),
      ),
    );
  }

  Widget _pager(ColorScheme scheme) {
    final atFirst = _page <= 0;
    final atLast = _page >= _pageCount - 1;
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            TextButton.icon(
              onPressed: (atFirst || _busy) ? null : () => _goto(_page - 1),
              icon: const Icon(Icons.chevron_left),
              label: const Text('Prev'),
            ),
            Text(
              'Page ${_page + 1} of $_pageCount',
              style: TextStyle(color: scheme.onSurfaceVariant),
            ),
            TextButton.icon(
              onPressed: (atLast || _busy) ? null : () => _goto(_page + 1),
              icon: const Icon(Icons.chevron_right),
              label: const Text('Next'),
            ),
          ],
        ),
      ),
    );
  }
}

class _ThreadCard extends StatelessWidget {
  const _ThreadCard({required this.bundle, required this.onTap});
  final MailBundle bundle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final title = bundle.topic.isNotEmpty
        ? bundle.topic
        : (bundle.subject.isNotEmpty ? bundle.subject : 'Email');
    final showSubtitle = bundle.topic.isNotEmpty && bundle.subject.isNotEmpty;
    final muted = [
      if (bundle.address.isNotEmpty) bundle.address,
      if (bundle.date.isNotEmpty) bundle.date,
      '${bundle.count} screenshot(s)',
    ].join(' - ');

    return Card(
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Text(
                      title,
                      style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
                    ),
                  ),
                  if (bundle.account.isNotEmpty) ...[
                    const SizedBox(width: 8),
                    Chip(
                      label: Text(bundle.account),
                      visualDensity: VisualDensity.compact,
                      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    ),
                  ],
                ],
              ),
              if (showSubtitle) ...[
                const SizedBox(height: 2),
                Text(
                  bundle.subject,
                  style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
                ),
              ],
              const SizedBox(height: 6),
              Text(
                muted,
                style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
