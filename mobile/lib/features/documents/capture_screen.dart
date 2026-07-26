/// ============================================================================
///  CaptureScreen — snap or pick a document, upload it, go review
/// ============================================================================
///
///  Purpose
///  -------
///  The camera-first heart of the mobile app: take a photo (or pick one), optionally
///  mark it vital (encrypted at rest), upload to the space. On success it surfaces the
///  extraction notice ("we read it — please review" / "auto-fill paused — add it") and
///  routes to the confirm screen with the created needs_review document.
///
///  Design
///  ------
///  image_picker for capture/gallery; upload via DocumentsApi. Upload errors auto-toast
///  through the ApiClient; success shows the document's own extractionNotice.
/// ============================================================================
library;

import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/image_edit.dart';
import '../../core/notice/notice_center.dart';
import '../../ui/widgets/help_card.dart';
import 'documents_api.dart';

class CaptureScreen extends ConsumerStatefulWidget {
  const CaptureScreen({super.key, required this.spaceId});
  final String spaceId;

  @override
  ConsumerState<CaptureScreen> createState() => _CaptureScreenState();
}

class _CaptureScreenState extends ConsumerState<CaptureScreen> {
  final _picker = ImagePicker();
  XFile? _picked;
  bool _vital = false;
  bool _busy = false;

  Future<void> _pick(ImageSource source) async {
    final x = await _picker.pickImage(source: source, imageQuality: 85, maxWidth: 2200);
    if (x == null) return;
    // Let the user straighten / crop the photo before it is uploaded and read.
    final edited = await cropImage(x.path);
    if (mounted) setState(() => _picked = XFile(edited));
  }

  Future<void> _upload() async {
    if (_picked == null) return;
    setState(() => _busy = true);
    try {
      final doc = await ref.read(documentsApiProvider).upload(
            spaceId: widget.spaceId,
            filePath: _picked!.path,
            vital: _vital,
          );
      final notice = doc.extractionNotice;
      if (notice != null) NoticeCenter.instance.show(notice);
      if (mounted) context.pushReplacement('/confirm', extra: doc);
    } catch (_) {
      // already surfaced as a toast by the ApiClient interceptor
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Add document')),
      body: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const HelpCard(
              title: 'How adding documents works',
              user:
                  "Snap a photo, paste, or pick files: a bill, receipt, policy or ID. Images are read automatically by AI and you just confirm the details next. PDFs are stored safely but are not auto-read yet, so you fill in their details yourself. You can add several files at once and each becomes its own document.",
              dev:
                  "Each file uploads separately; images are sent to a vision model only when the read toggle is on, and extraction runs after upload and always lands in needs_review. If the model errors or the daily budget is spent, a free stub fallback fills in so an upload never fails.",
            ),
            Expanded(
              child: Container(
                decoration: BoxDecoration(
                  color: scheme.surfaceContainerHighest.withValues(alpha: 0.4),
                  borderRadius: BorderRadius.circular(16),
                ),
                clipBehavior: Clip.antiAlias,
                child: _picked == null
                    ? Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.document_scanner_outlined,
                                size: 48, color: scheme.onSurfaceVariant,),
                            const SizedBox(height: 12),
                            Text('Snap a bill, receipt, or ID',
                                style: TextStyle(color: scheme.onSurfaceVariant),),
                          ],
                        ),
                      )
                    : Image.file(File(_picked!.path), fit: BoxFit.contain),
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _busy ? null : () => _pick(ImageSource.camera),
                    icon: const Icon(Icons.photo_camera_outlined),
                    label: const Text('Camera'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _busy ? null : () => _pick(ImageSource.gallery),
                    icon: const Icon(Icons.photo_library_outlined),
                    label: const Text('Gallery'),
                  ),
                ),
              ],
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: _vital,
              onChanged: _busy ? null : (v) => setState(() => _vital = v),
              title: const Text('Vital document'),
              subtitle: const Text('Encrypt at rest (passport, Aadhaar, PAN, policies)'),
            ),
            FilledButton(
              onPressed: (_picked == null || _busy) ? null : _upload,
              child: _busy
                  ? const SizedBox(
                      height: 20, width: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),)
                  : const Text('Upload & read'),
            ),
          ],
        ),
      ),
    );
  }
}
