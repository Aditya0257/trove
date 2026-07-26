/// ============================================================================
///  DocumentListScreen - documents in a space, filterable by category
/// ============================================================================
///  Purpose:  browse a space's documents; filter by category; see review status at
///            a glance; tap through to detail. Loads one page at a time (infinite
///            scroll) so a large vault is never fetched whole, swipe a row to trash
///            it, and open Trash from the overflow menu. Pull-to-refresh re-reads.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/models/document.dart';
import '../../core/models/space.dart';
import '../../core/notice/notice.dart';
import '../../core/notice/notice_center.dart';
import '../../ui/widgets/dev_drawer.dart';
import '../../ui/widgets/help_card.dart';
import 'documents_api.dart';

class DocumentListScreen extends ConsumerStatefulWidget {
  const DocumentListScreen({super.key, required this.space});
  final Space space;

  @override
  ConsumerState<DocumentListScreen> createState() => _DocumentListScreenState();
}

class _DocumentListScreenState extends ConsumerState<DocumentListScreen> {
  static const List<int> _pageSizes = [10, 25, 50, 100];

  final _scaffoldKey = GlobalKey<ScaffoldState>();
  int _pageSize = 25; // rows fetched per batch; user-selectable (matches web)
  String? _category; // null = all
  bool _filtersOpen = false; // category filter collapsed by default (it can be long)
  final List<TroveDocument> _docs = [];
  final ScrollController _scroll = ScrollController();
  int _page = 0;
  bool _loading = false;
  bool _end = false;
  bool _error = false;
  bool _initial = true;

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
      final batch = await ref.read(documentsApiProvider).list(
            spaceId: widget.space.id,
            category: _category,
            page: _page,
            size: _pageSize,
          );
      if (!mounted) return;
      setState(() {
        _docs.addAll(batch);
        _page++;
        if (batch.length < _pageSize) _end = true;
      });
    } catch (_) {
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
      _docs.clear();
      _page = 0;
      _end = false;
      _error = false;
      _initial = true;
    });
    await _loadNext();
  }

  void _setCategory(String? code) {
    if (_category == code) return;
    setState(() => _category = code);
    _reset();
  }

  void _setPageSize(int size) {
    if (_pageSize == size) return;
    setState(() => _pageSize = size);
    _reset();
  }

  /// Pick a category from the filter, then collapse the filter to give the list room.
  void _pickCategory(String? code) {
    setState(() => _filtersOpen = false);
    _setCategory(code);
  }

  /// Overflow-menu routing: Developer opens the right-side drawer, the rest navigate.
  void _onMenu(String v) {
    switch (v) {
      case 'developer':
        _scaffoldKey.currentState?.openEndDrawer();
      case 'account':
        context.push('/account');
      case 'manage':
        context.push('/space-manage', extra: widget.space);
      default:
        context.push('/$v', extra: widget.space.id);
    }
  }

  /// Confirm a swipe-to-delete. Returns true only if the user taps Move to Trash.
  Future<bool> _confirmDelete(TroveDocument doc) async {
    final name = doc.merchant ?? doc.category ?? 'this document';
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Move to Trash?'),
        content: Text('"$name" will move to Trash. You can restore it for 30 days.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
            ),
            child: const Text('Move to Trash'),
          ),
        ],
      ),
    );
    return ok ?? false;
  }

  Future<void> _delete(TroveDocument doc) async {
    try {
      await ref.read(documentsApiProvider).delete(doc.id);
      if (mounted) setState(() => _docs.removeWhere((d) => d.id == doc.id));
      NoticeCenter.instance.show(Notice.local(
        level: NoticeLevel.success,
        code: 'DELETED',
        userMessage: 'Moved to Trash - recoverable for 30 days.',
      ),);
    } catch (_) {
      await _reset(); // put the row back by reloading if the delete failed
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final categories = ref.watch(categoriesProvider);

    // Reload when a document is confirmed elsewhere (e.g. returning from the confirm
    // screen), so a newly added document appears without a manual pull-to-refresh.
    ref.listen(documentsChangedProvider, (_, __) {
      if (mounted) _reset();
    });

    return Scaffold(
      key: _scaffoldKey,
      endDrawer: const DeveloperDrawer(),
      appBar: AppBar(
        title: Text(widget.space.name),
        actions: [
          IconButton(
            tooltip: 'Search',
            icon: const Icon(Icons.search),
            onPressed: () => context.push('/search', extra: widget.space.id),
          ),
          PopupMenuButton<String>(
            tooltip: 'More',
            onSelected: _onMenu,
            itemBuilder: (context) => [
              const PopupMenuItem(
                value: 'reminders',
                child: ListTile(
                  leading: Icon(Icons.notifications_none),
                  title: Text('Reminders'),
                ),
              ),
              const PopupMenuItem(
                value: 'spend',
                child: ListTile(
                  leading: Icon(Icons.bar_chart_outlined),
                  title: Text('Spend'),
                ),
              ),
              if (!widget.space.isPersonal)
                const PopupMenuItem(
                  value: 'manage',
                  child: ListTile(
                    leading: Icon(Icons.manage_accounts_outlined),
                    title: Text('Manage space'),
                  ),
                ),
              const PopupMenuItem(
                value: 'chat',
                child: ListTile(
                  leading: Icon(Icons.auto_awesome_outlined),
                  title: Text('Ask your vault'),
                ),
              ),
              const PopupMenuItem(
                value: 'mail',
                child: ListTile(
                  leading: Icon(Icons.mail_outline),
                  title: Text('Mail'),
                ),
              ),
              const PopupMenuItem(
                value: 'backups',
                child: ListTile(
                  leading: Icon(Icons.cloud_done_outlined),
                  title: Text('Backups & data health'),
                ),
              ),
              const PopupMenuItem(
                value: 'trash',
                child: ListTile(
                  leading: Icon(Icons.delete_outline),
                  title: Text('Trash'),
                ),
              ),
              const PopupMenuItem(
                value: 'account',
                child: ListTile(
                  leading: Icon(Icons.account_circle_outlined),
                  title: Text('Your profile'),
                ),
              ),
              const PopupMenuItem(
                value: 'developer',
                child: ListTile(
                  leading: Icon(Icons.terminal),
                  title: Text('Developer'),
                ),
              ),
            ],
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push('/capture', extra: widget.space.id),
        icon: const Icon(Icons.add_a_photo_outlined),
        label: const Text('Add'),
      ),
      body: Column(
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(12, 12, 12, 0),
            child: HelpCard(
              title: 'Your documents',
              user:
                  "Everything you have filed in this space. Tap a document to review and confirm its details. 'needs_review' means the AI read it and it is waiting for you to confirm; 'confirmed' means you have verified it - only confirmed documents count toward Spend and Search. Swipe a row left to move it to Trash.",
              dev: null,
            ),
          ),
          categories.maybeWhen(
            data: (list) {
              // Emails live under Mail, not Documents (match the web) - drop the chip.
              final cats = list.where((c) => c.code != 'email').toList();
              var selected = 'All';
              for (final c in cats) {
                if (c.code == _category) selected = c.label;
              }
              return Column(
                children: [
                  InkWell(
                    onTap: () => setState(() => _filtersOpen = !_filtersOpen),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                      child: Row(
                        children: [
                          Icon(Icons.filter_list, size: 18, color: scheme.onSurfaceVariant),
                          const SizedBox(width: 8),
                          Text('Category: $selected',
                              style: const TextStyle(fontWeight: FontWeight.w600),),
                          const Spacer(),
                          PopupMenuButton<int>(
                            tooltip: 'Rows per page',
                            onSelected: _setPageSize,
                            itemBuilder: (_) => [
                              for (final s in _pageSizes)
                                PopupMenuItem(value: s, child: Text('$s per page')),
                            ],
                            child: Padding(
                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Text('$_pageSize / page',
                                      style: TextStyle(
                                        color: scheme.onSurfaceVariant,
                                        fontSize: 13,
                                      ),),
                                  Icon(Icons.arrow_drop_down,
                                      size: 18, color: scheme.onSurfaceVariant,),
                                ],
                              ),
                            ),
                          ),
                          const InfoTip(
                            title: 'Rows per page',
                            text:
                                'Documents load a page at a time instead of all at once, '
                                'which keeps a large vault fast. This sets how many load per '
                                'batch (10, 25, 50 or 100); the next batch loads automatically '
                                'as you scroll to the bottom.',
                          ),
                          const SizedBox(width: 4),
                          Icon(_filtersOpen ? Icons.expand_less : Icons.expand_more,
                              color: scheme.onSurfaceVariant,),
                        ],
                      ),
                    ),
                  ),
                  if (_filtersOpen)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
                      child: Wrap(
                        spacing: 6,
                        runSpacing: 2,
                        children: [
                          _chip('All', _category == null, () => _pickCategory(null)),
                          for (final c in cats)
                            _chip(c.label, _category == c.code, () => _pickCategory(c.code)),
                        ],
                      ),
                    ),
                  const Divider(height: 1),
                ],
              );
            },
            orElse: () => const SizedBox.shrink(),
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
    if (_error && _docs.isEmpty) {
      return ListView(children: [
        const SizedBox(height: 80),
        Center(
          child: Text("Couldn't load documents. Pull to retry.",
              style: TextStyle(color: scheme.onSurfaceVariant),),
        ),
      ],);
    }
    if (_docs.isEmpty) {
      return ListView(children: [
        const SizedBox(height: 80),
        Center(
          child: Text('No documents yet. Tap Add to scan one.',
              style: TextStyle(color: scheme.onSurfaceVariant),),
        ),
      ],);
    }
    return ListView.separated(
      controller: _scroll,
      itemCount: _docs.length + (_end ? 0 : 1),
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (_, i) {
        if (i >= _docs.length) {
          return const Padding(
            padding: EdgeInsets.symmetric(vertical: 20),
            child: Center(child: CircularProgressIndicator()),
          );
        }
        final doc = _docs[i];
        return Dismissible(
          key: ValueKey(doc.id),
          direction: DismissDirection.endToStart,
          background: Container(
            alignment: Alignment.centerRight,
            color: scheme.errorContainer,
            padding: const EdgeInsets.only(right: 20),
            child: Icon(Icons.delete_outline, color: scheme.onErrorContainer),
          ),
          // Ask before removing the row, so an accidental swipe never deletes silently.
          confirmDismiss: (_) => _confirmDelete(doc),
          onDismissed: (_) => _delete(doc),
          child: _DocTile(doc: doc),
        );
      },
    );
  }

  Widget _chip(String label, bool selected, VoidCallback onTap) => Padding(
        padding: const EdgeInsets.only(right: 6, bottom: 4),
        child: ChoiceChip(
          label: Text(label),
          selected: selected,
          onSelected: (_) => onTap(),
          visualDensity: VisualDensity.compact,
          materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
        ),
      );
}

class _DocTile extends StatelessWidget {
  const _DocTile({required this.doc});
  final TroveDocument doc;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final amount = doc.amount != null
        ? '${doc.currency ?? ''} ${doc.amount!.toStringAsFixed(2)}'.trim()
        : null;
    final date = doc.docDate?.toIso8601String().substring(0, 10);

    return ListTile(
      leading: CircleAvatar(
        backgroundColor: scheme.surfaceContainerHighest,
        child: Icon(doc.vital ? Icons.lock_outline : Icons.receipt_long_outlined,
            size: 20,),
      ),
      title: Text(doc.merchant ?? doc.category ?? 'Document'),
      subtitle: Text([doc.category, date].whereType<String>().join(' · ')),
      trailing: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (amount != null)
            Text(amount, style: const TextStyle(fontWeight: FontWeight.w600)),
          if (doc.needsReview)
            Container(
              margin: const EdgeInsets.only(top: 2),
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
              decoration: BoxDecoration(
                color: const Color(0xFFB8860B).withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(5),
              ),
              child: const Text('review',
                  style: TextStyle(fontSize: 10, color: Color(0xFFB8860B)),),
            ),
        ],
      ),
      onTap: () => context.push('/document', extra: doc),
    );
  }
}
