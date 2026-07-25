/// ============================================================================
///  MailThreadScreen - one filed email: metadata + its screenshots
/// ============================================================================
///
///  Purpose
///  -------
///  Shows a single filed email thread. The grouping metadata (account, address,
///  topic, subject, date) is read from the first document's `extra`, then every
///  screenshot in the thread is rendered from its `fileUrl`. Reached by go_router
///  with `extra: {spaceId, bundleId}`.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/models/document.dart';
import 'mail_api.dart';

// TODO(mail): filing new emails reuses the upload flow; not built here.

class MailThreadScreen extends ConsumerStatefulWidget {
  const MailThreadScreen({super.key, required this.spaceId, required this.bundleId});
  final String spaceId;
  final String bundleId;

  @override
  ConsumerState<MailThreadScreen> createState() => _MailThreadScreenState();
}

class _MailThreadScreenState extends ConsumerState<MailThreadScreen> {
  bool _busy = false;
  bool _failed = false;
  List<TroveDocument>? _docs;

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
      final docs = await _api.thread(widget.spaceId, widget.bundleId);
      if (mounted) setState(() => _docs = docs);
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
      if (mounted) setState(() => _failed = true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Email thread')),
      body: _body(scheme),
    );
  }

  Widget _body(ColorScheme scheme) {
    if (_busy && _docs == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_failed && _docs == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            "Couldn't load this email. Pull back and try again.",
            textAlign: TextAlign.center,
            style: TextStyle(color: scheme.onSurfaceVariant),
          ),
        ),
      );
    }
    final docs = _docs ?? const <TroveDocument>[];
    if (docs.isEmpty) {
      return Center(
        child: Text(
          'This email has no screenshots.',
          style: TextStyle(color: scheme.onSurfaceVariant),
        ),
      );
    }
    final images = docs.where((d) => (d.fileUrl ?? '').isNotEmpty).toList();
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _metaCard(scheme, docs.first),
        const SizedBox(height: 12),
        for (final doc in images) ...[
          _imageCard(scheme, doc),
          const SizedBox(height: 12),
        ],
      ],
    );
  }

  Widget _metaCard(ColorScheme scheme, TroveDocument first) {
    final extra = first.extra ?? const <String, dynamic>{};
    String? v(String key) {
      final raw = extra[key];
      final s = raw?.toString().trim() ?? '';
      return s.isEmpty ? null : s;
    }

    final rows = <Widget>[
      if (v('mailTopic') != null) _row(scheme, 'Topic', v('mailTopic')!),
      if (v('mailSubject') != null) _row(scheme, 'Subject', v('mailSubject')!),
      if (v('mailAccount') != null) _row(scheme, 'Account', v('mailAccount')!),
      if (v('mailAddress') != null) _row(scheme, 'From', v('mailAddress')!),
      if (v('mailDate') != null) _row(scheme, 'Date', v('mailDate')!),
    ];
    if (rows.isEmpty) return const SizedBox.shrink();

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

  Widget _row(ColorScheme scheme, String k, String val) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 80,
              child: Text(
                k,
                style: TextStyle(fontWeight: FontWeight.w600, color: scheme.onSurfaceVariant),
              ),
            ),
            Expanded(child: Text(val)),
          ],
        ),
      );

  Widget _imageCard(ColorScheme scheme, TroveDocument doc) {
    final placeholder = Container(
      height: 200,
      color: scheme.surfaceContainerHighest,
      child: const Center(child: Icon(Icons.broken_image_outlined, size: 40)),
    );
    final image = Image.network(
      doc.fileUrl!,
      fit: BoxFit.contain,
      loadingBuilder: (context, child, progress) {
        if (progress == null) return child;
        return SizedBox(
          height: 200,
          child: Center(
            child: CircularProgressIndicator(
              value: progress.expectedTotalBytes != null
                  ? progress.cumulativeBytesLoaded / progress.expectedTotalBytes!
                  : null,
            ),
          ),
        );
      },
      errorBuilder: (_, __, ___) => placeholder,
    );
    return Card(
      clipBehavior: Clip.antiAlias,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(12),
        // Match DocumentDetailScreen: wrap the image so it pinch-zooms and pans.
        // A tap opens the same image full-screen for a larger zoom canvas.
        child: GestureDetector(
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => _FullScreenImage(url: doc.fileUrl!),
              fullscreenDialog: true,
            ),
          ),
          child: _zoomable(image),
        ),
      ),
    );
  }

  /// Wraps a preview so it pinch-zooms and pans, matching DocumentDetailScreen.
  Widget _zoomable(Widget child) => InteractiveViewer(
        minScale: 0.8,
        maxScale: 4,
        child: Center(child: child),
      );
}

/// A single screenshot opened full-screen for a larger zoom canvas. Reuses the
/// same InteractiveViewer bounds as the inline preview.
class _FullScreenImage extends StatelessWidget {
  const _FullScreenImage({required this.url});
  final String url;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
      ),
      body: InteractiveViewer(
        minScale: 0.8,
        maxScale: 4,
        child: Center(
          child: Image.network(
            url,
            fit: BoxFit.contain,
            errorBuilder: (_, __, ___) => const Icon(
              Icons.broken_image_outlined,
              size: 48,
              color: Colors.white70,
            ),
          ),
        ),
      ),
    );
  }
}
