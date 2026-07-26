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
  final _notes = TextEditingController();

  final List<XFile> _shots = [];
  // The email's date, chosen via showDatePicker and shown/stored as YYYY-MM-DD.
  DateTime? _date;
  bool _busy = false;
  // Human-readable filing progress while the bundle uploads ("Filing 2 of 3...").
  String? _progress;
  // Generated once and reused across retries, so screenshots filed on a second
  // attempt (after a partial failure) still join the same thread.
  String? _bundleId;

  @override
  void dispose() {
    _account.dispose();
    _address.dispose();
    _topic.dispose();
    _subject.dispose();
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

  /// A date as YYYY-MM-DD, matching how the documents pipeline stores dates.
  String? _iso(DateTime? d) => d?.toIso8601String().substring(0, 10);

  /// Open a calendar to pick the email's date (mirrors ConfirmScreen's picker).
  Future<void> _pickDate() async {
    if (_busy) return;
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _date ?? now,
      firstDate: DateTime(2000),
      lastDate: DateTime(now.year + 5),
    );
    if (picked != null) setState(() => _date = picked);
  }

  /// Pick one or more screenshots from the gallery.
  Future<void> _addFromGallery() async {
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

  /// Take a photo with the camera (e.g. a printed letter, or a screen you cannot
  /// screenshot). Added to the same queue as gallery picks.
  Future<void> _takePhoto() async {
    if (_busy) return;
    final shot = await _picker.pickImage(source: ImageSource.camera, imageQuality: 85, maxWidth: 2200);
    if (shot != null) setState(() => _shots.add(shot));
  }

  void _remove(int index) {
    setState(() => _shots.removeAt(index));
  }

  Future<void> _save() async {
    if (_busy || _shots.isEmpty) return;
    final date = _iso(_date);
    final notes = _notes.text.trim();
    final account = _account.text.trim();
    final address = _address.text.trim();
    final topic = _topic.text.trim();
    final subject = _subject.text.trim();
    final bundleId = _bundleId ??= _newBundleId();
    final api = ref.read(documentsApiProvider);

    // Work over a snapshot and record which shots actually filed. If one fails
    // partway, the already-filed shots are dropped from the queue so a retry only
    // re-sends the rest - the backend rejects a re-upload of the same file as a
    // duplicate, which is exactly the "doc already exists" error we are avoiding.
    final pending = List<XFile>.from(_shots);
    final filed = <XFile>[];

    setState(() {
      _busy = true;
      _progress = null;
    });
    try {
      for (var i = 0; i < pending.length; i++) {
        if (mounted) setState(() => _progress = 'Filing ${i + 1} of ${pending.length}...');
        // extract:false so the async AI reader never overwrites the email category/bundle.
        final doc = await api.upload(
          spaceId: widget.spaceId,
          filePath: pending[i].path,
          extract: false,
        );
        await api.confirm(
          doc.id,
          category: 'email',
          docDate: date,
          extra: {
            'mailBundleId': bundleId,
            'mailAccount': account,
            'mailAddress': address,
            'mailTopic': topic,
            'mailSubject': subject,
            'mailDate': date ?? '',
            if (notes.isNotEmpty) 'notes': notes,
          },
        );
        filed.add(pending[i]);
      }
      _toast(NoticeLevel.success, 'MAIL_FILED', 'Email filed.');
      if (mounted) context.pop();
    } catch (_) {
      // The API client already surfaced the failure as a toast. Drop the shots that
      // did file so a retry sends only the ones that still need filing.
      if (mounted) setState(() => _shots.removeWhere(filed.contains));
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _progress = null;
        });
      }
    }
  }

  /// A tap-to-open date field: shows the picked date as YYYY-MM-DD, or a
  /// muted "Pick a date" hint when empty. Opens showDatePicker on tap.
  Widget _dateField() {
    final scheme = Theme.of(context).colorScheme;
    final has = _date != null;
    return InkWell(
      onTap: _busy ? null : _pickDate,
      child: InputDecorator(
        decoration: const InputDecoration(labelText: 'Date'),
        child: Text(
          has ? _iso(_date)! : 'Pick a date',
          style: has ? null : TextStyle(color: scheme.onSurfaceVariant),
        ),
      ),
    );
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
            decoration: const InputDecoration(
              labelText: 'Account',
              hintText: 'e.g. Personal or Office',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _address,
            keyboardType: TextInputType.emailAddress,
            decoration: const InputDecoration(
              labelText: 'Address (email inbox)',
              hintText: 'you@example.com',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _topic,
            decoration: const InputDecoration(
              labelText: 'Topic',
              hintText: 'e.g. Plum Insurance',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _subject,
            decoration: const InputDecoration(
              labelText: 'Subject',
              hintText: 'the exact subject line',
            ),
          ),
          const SizedBox(height: 12),
          _dateField(),
          const SizedBox(height: 12),
          TextField(
            controller: _notes,
            minLines: 2,
            maxLines: 5,
            decoration: const InputDecoration(
              labelText: 'Notes (optional)',
              hintText: 'anything to find it by later',
              alignLabelWithHint: true,
            ),
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _busy ? null : _takePhoto,
                  icon: const Icon(Icons.photo_camera_outlined),
                  label: const Text('Take photo'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _busy ? null : _addFromGallery,
                  icon: const Icon(Icons.photo_library_outlined),
                  label: const Text('Gallery'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          if (_shots.isEmpty)
            Text(
              'Take a photo or add screenshots of the email to file it.',
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
