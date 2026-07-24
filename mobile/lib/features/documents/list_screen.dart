/// ============================================================================
///  DocumentListScreen — documents in a space, filterable by category
/// ============================================================================
///  Purpose:  browse a space's documents; filter by category; see review status at
///            a glance; tap through to detail. Pull-to-refresh re-reads the index.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/models/document.dart';
import '../../core/models/space.dart';
import 'documents_api.dart';

class DocumentListScreen extends ConsumerStatefulWidget {
  const DocumentListScreen({super.key, required this.space});
  final Space space;

  @override
  ConsumerState<DocumentListScreen> createState() => _DocumentListScreenState();
}

class _DocumentListScreenState extends ConsumerState<DocumentListScreen> {
  String? _category; // null = all

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final docs = ref.watch(
      documentsProvider((spaceId: widget.space.id, category: _category)),
    );
    final categories = ref.watch(categoriesProvider);

    return Scaffold(
      appBar: AppBar(
        title: Text(widget.space.name),
        actions: [
          IconButton(
            tooltip: 'Reminders',
            icon: const Icon(Icons.notifications_none),
            onPressed: () => context.push('/reminders', extra: widget.space.id),
          ),
          IconButton(
            tooltip: 'Search',
            icon: const Icon(Icons.search),
            onPressed: () => context.push('/search', extra: widget.space.id),
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
          SizedBox(
            height: 52,
            child: categories.maybeWhen(
              data: (list) => ListView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 12),
                children: [
                  _chip('All', _category == null, () => setState(() => _category = null)),
                  for (final c in list)
                    _chip(c.label, _category == c.code,
                        () => setState(() => _category = c.code),),
                ],
              ),
              orElse: () => const SizedBox.shrink(),
            ),
          ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: () => ref.refresh(documentsProvider(
                (spaceId: widget.space.id, category: _category),
              ).future,),
              child: docs.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (_, __) => ListView(children: [
                  const SizedBox(height: 80),
                  Center(
                    child: Text('Couldn\'t load documents. Pull to retry.',
                        style: TextStyle(color: scheme.onSurfaceVariant),),
                  ),
                ],),
                data: (list) => list.isEmpty
                    ? ListView(children: [
                        const SizedBox(height: 80),
                        Center(
                          child: Text('No documents yet. Tap Add to scan one.',
                              style: TextStyle(color: scheme.onSurfaceVariant),),
                        ),
                      ],)
                    : ListView.separated(
                        itemCount: list.length,
                        separatorBuilder: (_, __) => const Divider(height: 1),
                        itemBuilder: (_, i) => _DocTile(doc: list[i]),
                      ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _chip(String label, bool selected, VoidCallback onTap) => Padding(
        padding: const EdgeInsets.only(right: 8, top: 8, bottom: 8),
        child: ChoiceChip(
          label: Text(label),
          selected: selected,
          onSelected: (_) => onTap(),
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
