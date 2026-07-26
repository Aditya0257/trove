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
  // One page per fetch; more load as you scroll (matches the Documents list so both
  // screens behave the same way - no odd "Page 1 of 1" pager).
  static const int _size = 25;

  final ScrollController _scroll = ScrollController();
  final List<MailBundle> _bundles = [];
  int _page = 0;
  bool _loading = false;
  bool _end = false;
  bool _error = false;
  bool _initial = true;

  MailApi get _api => ref.read(mailApiProvider);

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    _loadNext();
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (_scroll.position.pixels >= _scroll.position.maxScrollExtent - 320) {
      _loadNext();
    }
  }

  Future<void> _loadNext() async {
    if (_loading || _end) return;
    setState(() {
      _loading = true;
      _error = false;
    });
    try {
      final page = await _api.bundles(widget.spaceId, page: _page, size: _size);
      if (!mounted) return;
      setState(() {
        _bundles.addAll(page.bundles);
        _page++;
        if (page.bundles.length < _size) _end = true;
      });
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
      if (mounted) setState(() => _error = true);
    } finally {
      if (mounted) {
        setState(() {
          _loading = false;
          _initial = false;
        });
      }
    }
  }

  Future<void> _reset() async {
    setState(() {
      _bundles.clear();
      _page = 0;
      _end = false;
      _error = false;
      _initial = true;
    });
    await _loadNext();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Mail')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          await context.push('/mail-compose', extra: widget.spaceId);
          if (mounted) _reset(); // show a newly filed thread on return
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
                  "Emails you have filed, grouped into threads. Each thread can hold several screenshots of the same email. Tap a thread to view its screenshots and details. More threads load as you scroll down.",
              dev: null,
            ),
          ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: _reset,
              child: _body(scheme),
            ),
          ),
        ],
      ),
    );
  }

  Widget _body(ColorScheme scheme) {
    if (_initial && _loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error && _bundles.isEmpty) {
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
    if (_bundles.isEmpty) {
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
      controller: _scroll,
      padding: const EdgeInsets.all(12),
      itemCount: _bundles.length + (_end ? 0 : 1),
      itemBuilder: (_, i) {
        if (i >= _bundles.length) {
          return const Padding(
            padding: EdgeInsets.symmetric(vertical: 20),
            child: Center(child: CircularProgressIndicator()),
          );
        }
        return _ThreadCard(
          bundle: _bundles[i],
          onTap: () => context.push('/mail-thread', extra: {
            'spaceId': widget.spaceId,
            'bundleId': _bundles[i].bundleId,
          },),
        );
      },
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
