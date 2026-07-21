/// ============================================================================
///  DocumentDetailScreen — one document: image + fields + review action
/// ============================================================================
///
///  Purpose
///  -------
///  Shows a document in full: the image (a presigned URL for normal docs, or the
///  decrypt-stream bytes for vital ones), the extracted/confirmed fields, the
///  extraction notice, and — if still needs_review — a jump to the confirm screen.
///
///  Design
///  ------
///  Re-fetches by id for a fresh presigned URL + latest status. Vital documents are
///  never handed a presigned URL (that would leak ciphertext); their bytes come from
///  `GET /api/documents/{id}/content` via the authed client.
/// ============================================================================
library;

import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/models/document.dart';
import '../../core/providers.dart';
import 'documents_api.dart';

final _detailProvider = FutureProvider.autoDispose
    .family<TroveDocument, String>((ref, id) => ref.watch(documentsApiProvider).get(id));

final _vitalBytesProvider = FutureProvider.autoDispose
    .family<List<int>, String>((ref, id) =>
        ref.watch(apiClientProvider).getBytes('/api/documents/$id/content'),);

class DocumentDetailScreen extends ConsumerWidget {
  const DocumentDetailScreen({super.key, required this.initial});
  final TroveDocument initial;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final fresh = ref.watch(_detailProvider(initial.id));
    final doc = fresh.asData?.value ?? initial;
    final scheme = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: Text(doc.merchant ?? doc.category ?? 'Document'),
        actions: [
          if (doc.needsReview)
            TextButton(
              onPressed: () => context.push('/confirm', extra: doc),
              child: const Text('Review'),
            ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          AspectRatio(
            aspectRatio: 3 / 4,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: _DocImage(doc: doc),
            ),
          ),
          const SizedBox(height: 16),
          if (doc.extractionNotice != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Text(doc.extractionNotice!.userMessage,
                  style: TextStyle(color: scheme.onSurfaceVariant),),
            ),
          _row('Status', doc.needsReview ? 'Needs review' : 'Confirmed'),
          _row('Category', doc.category ?? '—'),
          _row('Merchant', doc.merchant ?? '—'),
          _row('Amount',
              doc.amount != null ? '${doc.currency ?? ''} ${doc.amount!.toStringAsFixed(2)}'.trim() : '—',),
          _row('Document date',
              doc.docDate?.toIso8601String().substring(0, 10) ?? '—',),
          _row('Due date', doc.dueDate?.toIso8601String().substring(0, 10) ?? '—'),
          if (doc.vital) _row('Protection', 'Encrypted at rest'),
          if ((doc.rawText ?? '').isNotEmpty) ...[
            const SizedBox(height: 12),
            Text('What we read',
                style: TextStyle(fontWeight: FontWeight.w700, color: scheme.onSurfaceVariant),),
            const SizedBox(height: 4),
            Text(doc.rawText!, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          ],
        ],
      ),
    );
  }

  Widget _row(String k, String v) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(width: 120, child: Text(k, style: const TextStyle(fontWeight: FontWeight.w600))),
            Expanded(child: Text(v)),
          ],
        ),
      );
}

class _DocImage extends ConsumerWidget {
  const _DocImage({required this.doc});
  final TroveDocument doc;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final placeholder = Container(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: const Center(child: Icon(Icons.image_outlined, size: 40)),
    );
    if (doc.vital) {
      final bytes = ref.watch(_vitalBytesProvider(doc.id));
      return bytes.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, __) => placeholder,
        data: (b) => Image.memory(
          Uint8List.fromList(b),
          fit: BoxFit.contain,
          errorBuilder: (_, __, ___) => placeholder,
        ),
      );
    }
    if (doc.fileUrl != null && doc.fileUrl!.isNotEmpty) {
      return Image.network(doc.fileUrl!,
          fit: BoxFit.contain, errorBuilder: (_, __, ___) => placeholder,);
    }
    return placeholder;
  }
}
