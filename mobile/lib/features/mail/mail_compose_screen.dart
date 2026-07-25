/// ============================================================================
///  MailComposeScreen - file the important emails you screenshot ("file an email")
/// ============================================================================
///
///  Purpose
///  -------
///  Some things that matter arrive as email, not as a bill you can snap: an order
///  confirmation, a policy note, a booking. This screen lets a person file one of
///  those by screenshotting it. Several screenshots of the same email are kept
///  together as one thread via a shared bundle id, and the surrounding details
///  (account, inbox address, topic, subject, date, notes) are captured by hand.
///
///  Design
///  ------
///  Reuses the documents pipeline: each queued screenshot is uploaded as its own
///  document, then confirmed into the 'email' category carrying the shared
///  mailBundleId plus the typed fields in `extra`. AI reading is deliberately off
///  for mail - the human supplies the fields, so there is nothing to auto-read.
///  image_picker queues the screenshots; DocumentsApi does the upload + confirm.
/// ============================================================================
library;

import 'dart:io';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/notice/notice.dart';
import '../../core/notice/notice_center.dart';
import '../../ui/widgets/help_card.dart';
import '../documents/documents_api.dart';

class MailComposeScreen extends ConsumerStatefulWidget {
  const MailComposeScreen({required this.spaceId, super.key});
  final String spaceId;

  @override
  ConsumerState<MailComposeScreen> createState() => _MailComposeScreenState();
}

class _MailComposeScreenState extends ConsumerState<MailComposeScreen> {
  final _picker = ImagePicker();
  final _account = TextEditingController();
  final _address = TextEditingController();
  final _topic = TextEditingController();
  final _subject = TextEditingController();
  final _date = TextEditingController();
  final _notes = TextEditingController();

  final List<XFile> _shots = [];
  bool _busy = false;
  // Human-readable filing progress while the bundle uploads ("Filing 2 of 3...").
  String? _progress;

  @override
  void dispose() {
    _account.dispose();
    _address.dispose();
    _topic.dispose();
    _subject.dispose();
    _date.dispose();
    _notes.dispose();
    super.dispose();
  }

  void _toast(NoticeLevel level, String code, String message) {
    NoticeCenter.instance.show(Notice.local(level: level, code: code, userMessage: message));
  }

  /// A short unique id shared by every screenshot of the same email, so they stay
  /// together as one thread. Time + random suffix avoids pulling in the uuid package.
  String _newBundleId() {
    final stamp = DateTime.now().microsecondsSinceEpoch.toString();
    final suffix = Random().nextInt(0x7fffffff).toRadixString(36);
    return 'mail-$stamp-$suffix';
  }

  Future<void> _addScreenshots() async {
    if (_busy) return;
    try {
      final picked = await _picker.pickMultiImage(imageQuality: 85, maxWidth: 2200);
      if (picked.isNotEmpty) setState(() => _shots.addAll(picked));
    } catch (_) {
      // Some platforms/pickers can reject multi-select; fall back to a single pick.
      final one = await _picker.pickImage(source: ImageSource.gallery, imageQuality: 85, maxWidth: 2200);
      if (one != null) setState(() => _shots.add(one));
    }
  }

  void _remove(int index) {
    setState(() => _shots.removeAt(index));
  }

  Future<void> _save() async {
    if (_busy || _shots.isEmpty) return;
    final date = _date.text.trim();
    final notes = _notes.text.trim();
    final account = _account.text.trim();
    final address = _address.text.trim();
    final topic = _topic.text.trim();
    final subject = _subject.text.trim();
    final bundleId = _newBundleId();
    final api = ref.read(documentsApiProvider);

    setState(() {
      _busy = true;
      _progress = null;
    });
    try {
      for (var i = 0; i < _shots.length; i++) {
        if (mounted) setState(() => _progress = 'Filing ${i + 1} of ${_shots.length}...');
        final doc = await api.upload(spaceId: widget.spaceId, filePath: _shots[i].path);
        await api.confirm(
          doc.id,
          category: 'email',
          docDate: date.isNotEmpty ? date : null,
          extra: {
            'mailBundleId': bundleId,
            'mailAccount': account,
            'mailAddress': address,
            'mailTopic': topic,
            'mailSubject': subject,
            'mailDate': date,
            if (notes.isNotEmpty) 'notes': notes,
          },
        );
      }
      _toast(NoticeLevel.success, 'MAIL_FILED', 'Email filed.');
      if (mounted) context.pop();
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _progress = null;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('File an email')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const HelpCard(
            title: 'How filing an email works',
            user:
                "File the important emails you screenshot: an order confirmation, a policy note, a booking. Take one or more screenshots of the same email and they are kept together as one thread. Fill in the account, inbox address, topic, subject and date so you can find it later. AI reading is off for mail - you supply the details, so nothing is read automatically.",
            dev:
                "Each screenshot uploads as its own document, then is confirmed into the 'email' category carrying a shared mailBundleId plus the typed fields in `extra`. No vision model runs for these; the human-entered fields are the source of truth.",
          ),
          TextField(
            controller: _account,
            decoration: const InputDecoration(labelText: 'Account'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _address,
            keyboardType: TextInputType.emailAddress,
            decoration: const InputDecoration(labelText: 'Address (email inbox)'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _topic,
            decoration: const InputDecoration(labelText: 'Topic'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _subject,
            decoration: const InputDecoration(labelText: 'Subject'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _date,
            keyboardType: TextInputType.datetime,
            decoration: const InputDecoration(
              labelText: 'Date',
              hintText: 'YYYY-MM-DD',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _notes,
            minLines: 2,
            maxLines: 5,
            decoration: const InputDecoration(
              labelText: 'Notes (optional)',
              alignLabelWithHint: true,
            ),
          ),
          const SizedBox(height: 16),
          OutlinedButton.icon(
            onPressed: _busy ? null : _addScreenshots,
            icon: const Icon(Icons.add_photo_alternate_outlined),
            label: const Text('Add screenshots'),
          ),
          const SizedBox(height: 12),
          if (_shots.isEmpty)
            Text(
              'Add at least one screenshot of the email to file it.',
              style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
            )
          else
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (var i = 0; i < _shots.length; i++)
                  _Thumb(
                    file: File(_shots[i].path),
                    onRemove: _busy ? null : () => _remove(i),
                  ),
              ],
            ),
          const SizedBox(height: 20),
          FilledButton(
            onPressed: (_busy || _shots.isEmpty) ? null : _save,
            child: _busy
                ? Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const SizedBox(
                        height: 20,
                        width: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                      const SizedBox(width: 12),
                      Text(_progress ?? 'Filing...'),
                    ],
                  )
                : const Text('Save'),
          ),
        ],
      ),
    );
  }
}

/// A small screenshot preview with a remove-x in its corner.
class _Thumb extends StatelessWidget {
  const _Thumb({required this.file, required this.onRemove});

  final File file;
  final VoidCallback? onRemove;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Stack(
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(10),
          child: Image.file(
            file,
            height: 84,
            width: 84,
            fit: BoxFit.cover,
          ),
        ),
        Positioned(
          top: 2,
          right: 2,
          child: InkWell(
            customBorder: const CircleBorder(),
            onTap: onRemove,
            child: Container(
              padding: const EdgeInsets.all(2),
              decoration: BoxDecoration(
                color: scheme.surface.withValues(alpha: 0.85),
                shape: BoxShape.circle,
              ),
              child: Icon(Icons.close, size: 16, color: scheme.onSurface),
            ),
          ),
        ),
      ],
    );
  }
}
