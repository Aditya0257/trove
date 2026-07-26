/// ============================================================================
///  TrashScreen - deleted documents, recoverable for 30 days
/// ============================================================================
///  Purpose:  show trashed documents in a space and let the user restore one back
///            to the live vault or delete it forever. Mirrors the web Trash view.
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
  String? _busyId;

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
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete forever?'),
        content: Text('"$name" will be cleared from live storage. This cannot be undone.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: FilledButton.styleFrom(backgroundColor: Theme.of(ctx).colorScheme.error),
            child: const Text('Delete forever'),
          ),
        ],
      ),
    );
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

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Trash')),
      body: RefreshIndicator(
        onRefresh: _load,
        child: _body(scheme),
      ),
    );
  }

  Widget _body(ColorScheme scheme) {
    if (_loading && _docs == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final docs = _docs ?? const <TroveDocument>[];
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        const HelpCard(
          title: 'How Trash works',
          user:
              'Deleted documents stay here for 30 days, then they are permanently removed. '
              'Restore anything before then, or delete it forever now. Your other backup '
              'copies mean an accidental delete is never the end of the world.\n\n'
              'The two buttons on each row: the circular arrow restores the document back '
              'to your vault; the red bin deletes it forever (with a confirm first).',
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
                leading: Icon(doc.vital ? Icons.lock_outline : Icons.receipt_long_outlined),
                title: Text(doc.merchant ?? doc.category ?? 'Document'),
                subtitle: Text(doc.category ?? '-'),
                trailing: _busyId == doc.id
                    ? const SizedBox(
                        width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2),)
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
