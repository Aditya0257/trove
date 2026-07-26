/// ============================================================================
///  TrashScreen - deleted documents, recoverable for 30 days
/// ============================================================================
///  Purpose:  show trashed documents in a space and let the user restore or delete
///            them - one at a time, several at once (tick the rows), or all at once
///            ("Delete all"). Mirrors the web Trash view.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/models/document.dart';
import '../../core/notice/notice.dart';
import '../../core/notice/notice_center.dart';
import '../../ui/widgets/help_card.dart';
import 'documents_api.dart';

class TrashScreen extends ConsumerStatefulWidget {
  const TrashScreen({required this.spaceId, super.key});
  final String spaceId;

  @override
  ConsumerState<TrashScreen> createState() => _TrashScreenState();
}

class _TrashScreenState extends ConsumerState<TrashScreen> {
  List<TroveDocument>? _docs;
  bool _loading = true;
  bool _error = false;
  String? _busyId; // single-row op in progress
  bool _bulkBusy = false; // a bulk (selected / all) op in progress
  final Set<String> _selected = {};

  DocumentsApi get _api => ref.read(documentsApiProvider);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = false;
      _selected.clear();
    });
    try {
      final list = await _api.trash(widget.spaceId);
      if (mounted) setState(() => _docs = list);
    } catch (_) {
      if (mounted) setState(() => _error = true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _toast(NoticeLevel level, String code, String msg) {
    NoticeCenter.instance.show(Notice.local(level: level, code: code, userMessage: msg));
  }

  void _toggle(String id) {
    setState(() => _selected.contains(id) ? _selected.remove(id) : _selected.add(id));
  }

  Future<void> _restore(TroveDocument doc) async {
    setState(() => _busyId = doc.id);
    try {
      await _api.restore(doc.id);
      if (mounted) setState(() => _docs?.removeWhere((d) => d.id == doc.id));
      _toast(NoticeLevel.success, 'RESTORED', 'Document restored.');
    } catch (_) {
      // surfaced by the notice interceptor
    } finally {
      if (mounted) setState(() => _busyId = null);
    }
  }

  Future<void> _purge(TroveDocument doc) async {
    final name = doc.merchant ?? doc.category ?? 'this document';
    final ok = await _confirm('Delete forever?',
        '"$name" will be cleared from live storage. This cannot be undone.', 'Delete forever',);
    if (ok != true) return;
    setState(() => _busyId = doc.id);
    try {
      await _api.purge(doc.id);
      if (mounted) setState(() => _docs?.removeWhere((d) => d.id == doc.id));
      _toast(NoticeLevel.info, 'PURGED', 'Permanently deleted.');
    } catch (_) {
      // surfaced by the notice interceptor
    } finally {
      if (mounted) setState(() => _busyId = null);
    }
  }

  Future<void> _restoreSelected() async {
    final ids = _selected.toList();
    if (ids.isEmpty || _bulkBusy) return;
    setState(() => _bulkBusy = true);
    var done = 0;
    for (final id in ids) {
      try {
        await _api.restore(id);
        done++;
        if (mounted) setState(() => _docs?.removeWhere((d) => d.id == id));
      } catch (_) {
        // keep going; a failed one just stays in the trash
      }
    }
    if (mounted) setState(() { _selected.clear(); _bulkBusy = false; });
    _toast(NoticeLevel.success, 'RESTORED_MANY', 'Restored $done document${done == 1 ? '' : 's'}.');
  }

  Future<void> _purgeSelected() async {
    final ids = _selected.toList();
    if (ids.isEmpty || _bulkBusy) return;
    final ok = await _confirm('Delete ${ids.length} forever?',
        '${ids.length} document${ids.length == 1 ? '' : 's'} will be cleared from live storage. This cannot be undone.',
        'Delete forever',);
    if (ok != true) return;
    await _bulkPurge(ids);
  }

  Future<void> _purgeAll() async {
    final ids = (_docs ?? const <TroveDocument>[]).map((d) => d.id).toList();
    if (ids.isEmpty || _bulkBusy) return;
    final ok = await _confirm('Empty Trash?',
        'All ${ids.length} document${ids.length == 1 ? '' : 's'} in Trash will be permanently deleted. This cannot be undone.',
        'Delete all',);
    if (ok != true) return;
    await _bulkPurge(ids);
  }

  Future<void> _bulkPurge(List<String> ids) async {
    setState(() => _bulkBusy = true);
    var done = 0;
    for (final id in ids) {
      try {
        await _api.purge(id);
        done++;
        if (mounted) setState(() => _docs?.removeWhere((d) => d.id == id));
      } catch (_) {
        // keep going
      }
    }
    if (mounted) setState(() { _selected.clear(); _bulkBusy = false; });
    _toast(NoticeLevel.info, 'PURGED_MANY', 'Deleted $done document${done == 1 ? '' : 's'}.');
  }

  Future<bool?> _confirm(String title, String message, String confirmLabel) => showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text(title),
          content: Text(message),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              style: FilledButton.styleFrom(backgroundColor: Theme.of(ctx).colorScheme.error),
              child: Text(confirmLabel),
            ),
          ],
        ),
      );

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final docs = _docs ?? const <TroveDocument>[];
    final selecting = _selected.isNotEmpty;
    return Scaffold(
      appBar: selecting
          ? AppBar(
              leading: IconButton(
                tooltip: 'Clear selection',
                icon: const Icon(Icons.close),
                onPressed: _bulkBusy ? null : () => setState(_selected.clear),
              ),
              title: Text('${_selected.length} selected'),
              actions: [
                IconButton(
                  tooltip: 'Restore selected',
                  icon: const Icon(Icons.restore),
                  onPressed: _bulkBusy ? null : _restoreSelected,
                ),
                IconButton(
                  tooltip: 'Delete selected',
                  icon: Icon(Icons.delete_forever, color: scheme.error),
                  onPressed: _bulkBusy ? null : _purgeSelected,
                ),
              ],
            )
          : AppBar(
              title: const Text('Trash'),
              actions: [
                if (docs.isNotEmpty)
                  IconButton(
                    tooltip: 'Delete all',
                    icon: const Icon(Icons.delete_sweep_outlined),
                    onPressed: _bulkBusy ? null : _purgeAll,
                  ),
              ],
            ),
      body: Column(
        children: [
          if (_bulkBusy) const LinearProgressIndicator(minHeight: 2),
          Expanded(
            child: RefreshIndicator(onRefresh: _load, child: _body(scheme, docs, selecting)),
          ),
        ],
      ),
    );
  }

  Widget _body(ColorScheme scheme, List<TroveDocument> docs, bool selecting) {
    if (_loading && _docs == null) {
      return const Center(child: CircularProgressIndicator());
    }
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        const HelpCard(
          title: 'How Trash works',
          user:
              'Deleted documents stay here for 30 days, then they are permanently removed. '
              'Restore anything before then, or delete it forever now. Your other backup '
              'copies mean an accidental delete is never the end of the world.\n\n'
              'Tick the rows to select several, then Restore or Delete them together from the '
              'top bar, or use "Delete all" to empty the Trash. Each row also has its own '
              'restore (circular arrow) and delete-forever (red bin) buttons.',
          dev: null,
        ),
        if (_error && _docs == null)
          Padding(
            padding: const EdgeInsets.only(top: 60),
            child: Center(
              child: Text("Couldn't load Trash. Pull to retry.",
                  style: TextStyle(color: scheme.onSurfaceVariant),),
            ),
          )
        else if (docs.isEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 60),
            child: Center(
              child: Text('Trash is empty.', style: TextStyle(color: scheme.onSurfaceVariant)),
            ),
          )
        else
          for (final doc in docs)
            Card(
              child: ListTile(
                onTap: _bulkBusy ? null : () => _toggle(doc.id),
                leading: Checkbox(
                  value: _selected.contains(doc.id),
                  onChanged: _bulkBusy ? null : (_) => _toggle(doc.id),
                ),
                title: Text(doc.merchant ?? doc.category ?? 'Document',
                    maxLines: 1, overflow: TextOverflow.ellipsis,),
                subtitle: Text(doc.category ?? '-'),
                trailing: _busyId == doc.id
                    ? const SizedBox(
                        width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2),)
                    // Per-row actions only when not in multi-select mode, to keep the row tidy.
                    : selecting
                        ? null
                        : Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              IconButton(
                                tooltip: 'Restore',
                                icon: const Icon(Icons.restore),
                                onPressed: () => _restore(doc),
                              ),
                              IconButton(
                                tooltip: 'Delete forever',
                                icon: Icon(Icons.delete_forever, color: scheme.error),
                                onPressed: () => _purge(doc),
                              ),
                            ],
                          ),
              ),
            ),
      ],
    );
  }
}
