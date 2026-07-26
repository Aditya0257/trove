/// ============================================================================
///  DocumentDetailScreen - one document: image + fields + review action
/// ============================================================================
///
///  Purpose
///  -------
///  Shows a document in full: the image (a presigned URL for normal docs, or the
///  decrypt-stream bytes for vital ones), the extracted/confirmed fields as a clean
///  key-value card, the extraction notice, the collapsed raw text, and - if still
///  needs_review - a jump to the confirm screen.
///
///  Design
///  ------
///  Re-fetches by id for a fresh presigned URL + latest status. Vital documents are
///  never handed a presigned URL (that would leak ciphertext); their bytes come from
///  `GET /api/documents/{id}/content` via the authed client. The preview is wrapped in
///  an InteractiveViewer so it pinch-zooms and pans; anything we cannot render inline
///  (PDF, or a protected/relative content URL) falls back to a clean placeholder card.
/// ============================================================================
library;

import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'package:pdfx/pdfx.dart';

import '../../core/models/document.dart';
import '../../core/notice/notice.dart';
import '../../core/notice/notice_center.dart';
import '../../core/providers.dart';
import '../../ui/widgets/help_card.dart';
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
        title: Text(
          doc.merchant ?? doc.category ?? 'Document',
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 18),
        ),
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
          const HelpCard(
            title: 'Reviewing a document',
            user:
                'Trove reads each document with AI and pre-fills the fields below so you '
                'do not have to type them. Nothing is final yet: a document only counts '
                'toward your spend, search and reminders once you confirm it. Tap Review '
                'to check and edit any field before confirming.',
            dev:
                'The extractor returns {category, merchant, docDate, amount, dueDate, '
                'rawText, confidence} and the record lands in needs_review. Confirm '
                'promotes it to confirmed; until then downstream jobs skip it.',
          ),
          AspectRatio(
            aspectRatio: 3 / 4,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: doc.isPdf ? _PdfPreview(doc: doc) : _DocImage(doc: doc),
            ),
          ),
          const SizedBox(height: 4),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              onPressed: () => _openOriginal(ref, doc),
              icon: const Icon(Icons.open_in_new, size: 18),
              label: Text(doc.isPdf ? 'Download / open PDF' : 'Download / open file'),
            ),
          ),
          const SizedBox(height: 8),
          if (doc.extractionNotice != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Text(doc.extractionNotice!.userMessage,
                  style: TextStyle(color: scheme.onSurfaceVariant),),
            ),
          _FieldsCard(doc: doc),
          if ((doc.rawText ?? '').isNotEmpty) ...[
            const SizedBox(height: 12),
            _RawTextSection(text: doc.rawText!),
          ],
        ],
      ),
    );
  }
}

/// The extracted/confirmed fields as a clean key-value card. Only present fields
/// are shown; a run-on paragraph this is not.
class _FieldsCard extends StatelessWidget {
  const _FieldsCard({required this.doc});
  final TroveDocument doc;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final amount = doc.amount != null
        ? '${doc.currency ?? ''} ${doc.amount!.toStringAsFixed(2)}'.trim()
        : null;

    final rows = <Widget?>[
      _row(scheme, 'Category', doc.category),
      _row(scheme, 'Merchant', doc.merchant),
      _row(scheme, 'Date', doc.docDate?.toIso8601String().substring(0, 10)),
      _row(scheme, 'Amount', amount),
      _row(scheme, 'Due date', doc.dueDate?.toIso8601String().substring(0, 10)),
      _row(scheme, 'Status', doc.needsReview ? 'Needs review' : 'Confirmed'),
      if (doc.vital) _row(scheme, 'Protection', 'Encrypted at rest'),
    ].whereType<Widget>().toList();

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: rows,
        ),
      ),
    );
  }

  /// Returns null when the value is empty so the caller can drop the row.
  Widget? _row(ColorScheme scheme, String label, String? value) {
    if (value == null || value.isEmpty) return null;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 120,
            child: Text(
              label,
              style: TextStyle(fontWeight: FontWeight.w600, color: scheme.onSurfaceVariant),
            ),
          ),
          Expanded(child: Text(value)),
        ],
      ),
    );
  }
}

/// The raw AI-read text, collapsed by default and rendered in a readable,
/// monospace-ish, selectable block.
class _RawTextSection extends StatelessWidget {
  const _RawTextSection({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      margin: EdgeInsets.zero,
      child: ExpansionTile(
        // Borderless shapes drop the default dividers without a Theme wrapper.
        shape: const RoundedRectangleBorder(side: BorderSide.none),
        collapsedShape: const RoundedRectangleBorder(side: BorderSide.none),
        tilePadding: const EdgeInsets.symmetric(horizontal: 16),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        title: const Text(
          'What the AI read (raw text)',
          style: TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
        ),
        children: [
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: scheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(8),
            ),
            child: SelectableText(
              text,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }
}

class _DocImage extends ConsumerWidget {
  const _DocImage({required this.doc});
  final TroveDocument doc;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (doc.vital) {
      final bytes = ref.watch(_vitalBytesProvider(doc.id));
      return bytes.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, __) => const _PreviewUnavailable(),
        data: (b) => _zoomable(
          Image.memory(
            Uint8List.fromList(b),
            fit: BoxFit.contain,
            errorBuilder: (_, __, ___) => const _PreviewUnavailable(),
          ),
        ),
      );
    }

    final url = doc.fileUrl;
    final isHttp = url != null &&
        (url.startsWith('http://') || url.startsWith('https://'));
    if (!isHttp) {
      // Null, empty, or a relative /api/documents/{id}/content path: we cannot
      // render it inline here (PDF or protected file).
      return const _PreviewUnavailable();
    }

    return _zoomable(
      Image.network(
        url,
        fit: BoxFit.contain,
        loadingBuilder: (context, child, progress) {
          if (progress == null) return child;
          final expected = progress.expectedTotalBytes;
          return Center(
            child: CircularProgressIndicator(
              value: expected != null ? progress.cumulativeBytesLoaded / expected : null,
            ),
          );
        },
        errorBuilder: (_, __, ___) => const _PreviewUnavailable(),
      ),
    );
  }

  /// Wraps the preview so it pinch-zooms and pans.
  Widget _zoomable(Widget child) => InteractiveViewer(
        minScale: 0.8,
        maxScale: 4,
        child: Center(child: child),
      );
}

/// Inline PDF preview (pinch-zoom, page through) rendered from the file bytes via
/// the authed content endpoint, so it works for normal and vital documents alike.
class _PdfPreview extends ConsumerStatefulWidget {
  const _PdfPreview({required this.doc});
  final TroveDocument doc;

  @override
  ConsumerState<_PdfPreview> createState() => _PdfPreviewState();
}

class _PdfPreviewState extends ConsumerState<_PdfPreview> {
  PdfControllerPinch? _controller;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    _controller = PdfControllerPinch(
      document: PdfDocument.openData(
        ref
            .read(documentsApiProvider)
            .contentBytes(widget.doc.id)
            .then((b) => Uint8List.fromList(b)),
      ),
    );
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_failed || _controller == null) return const _PreviewUnavailable();
    return PdfViewPinch(
      controller: _controller!,
      onDocumentError: (_) {
        if (mounted) setState(() => _failed = true);
      },
    );
  }
}

/// Downloads the original file to a temp path and opens it in the phone's system
/// viewer (also the "download" path: the file lands in the app's cache directory).
Future<void> _openOriginal(WidgetRef ref, TroveDocument doc) async {
  try {
    final bytes = await ref.read(documentsApiProvider).contentBytes(doc.id);
    final dir = await getTemporaryDirectory();
    final ext = doc.isPdf ? 'pdf' : 'jpg';
    final file = File('${dir.path}/trove-${doc.id}.$ext');
    await file.writeAsBytes(bytes, flush: true);
    final result = await OpenFilex.open(file.path, type: doc.mimeType);
    if (result.type != ResultType.done) {
      NoticeCenter.instance.show(Notice.local(
        level: NoticeLevel.warning,
        code: 'OPEN_FAIL',
        userMessage: 'Saved the file, but no app could open it: ${result.message}',
      ),);
    }
  } catch (_) {
    // Fetch failures are already surfaced by the API client.
  }
}

/// Shown when there is nothing we can render inline: a protected/encrypted file
/// behind a relative content URL, or a load failure.
class _PreviewUnavailable extends StatelessWidget {
  const _PreviewUnavailable();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      margin: EdgeInsets.zero,
      color: scheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Icon(Icons.description_outlined, size: 44, color: scheme.onSurfaceVariant),
            const SizedBox(height: 12),
            Text(
              "Preview isn't available in the app yet (PDF or protected file). "
              'The file is stored safely and viewable on the web.',
              textAlign: TextAlign.center,
              style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13, height: 1.5),
            ),
          ],
        ),
      ),
    );
  }
}
