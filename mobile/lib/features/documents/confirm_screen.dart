/// ============================================================================
///  ConfirmScreen — the sacred human-review step
/// ============================================================================
///
///  Purpose
///  -------
///  Trove never trusts extracted numbers silently. This screen shows what the model
///  read (with its notice: "review this" / "we couldn't read it — add it yourself"),
///  lets the user correct category / merchant / amount / dates, and confirms —
///  moving the document from needs_review to confirmed.
///
///  Design
///  ------
///  Prefilled from the document; category from `categoriesProvider`; the raw OCR text
///  is available in an expander for cross-checking. Confirm errors auto-toast; success
///  shows a calm confirmation and returns home.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/models/category.dart';
import '../../core/models/document.dart';
import '../../core/notice/notice.dart';
import '../../core/notice/notice_center.dart';
import '../../ui/theme.dart';
import '../../ui/widgets/help_card.dart';
import 'documents_api.dart';

class ConfirmScreen extends ConsumerStatefulWidget {
  const ConfirmScreen({super.key, required this.doc});
  final TroveDocument doc;

  @override
  ConsumerState<ConfirmScreen> createState() => _ConfirmScreenState();
}

class _ConfirmScreenState extends ConsumerState<ConfirmScreen> {
  // Controllers are stable objects; their `.text` is (re)populated from the current
  // document, so extraction that lands via polling can refill the fields after init.
  final TextEditingController _merchant = TextEditingController();
  final TextEditingController _amount = TextEditingController();
  final TextEditingController _currency = TextEditingController();
  String? _category;
  DateTime? _docDate;
  DateTime? _dueDate;
  DateTime? _warrantyUntil;
  bool _vital = false;
  bool _busy = false;

  /// The latest known version of the document. Starts as the one routed in and is
  /// replaced when extraction polling lands, so raw text / notice / anomaly / the
  /// `extra` trail all reflect what the server actually extracted.
  late TroveDocument _doc = widget.doc;

  /// True while we poll for a still-running server-side extraction.
  bool _reading = false;

  static const int _maxPollAttempts = 20;
  static const Duration _pollInterval = Duration(milliseconds: 1500);

  static DateTime? _parseIso(Object? v) =>
      (v is String && v.isNotEmpty) ? DateTime.tryParse(v) : null;

  /// The anomaly verdict stored on the document at its last confirm, if flagged.
  Map<String, dynamic>? get _anomaly {
    final a = _doc.extra?['anomaly'];
    return (a is Map && a['anomaly'] == true) ? a.cast<String, dynamic>() : null;
  }

  @override
  void initState() {
    super.initState();
    _populate(widget.doc);
    // A freshly uploaded document arrives before extraction finishes: no confidence
    // yet and (for non-encrypted files) fields still blank. Show a "reading" banner
    // and poll until the extraction lands, then refill. Encrypted docs are never
    // auto-extracted, so leave them for manual entry.
    if (widget.doc.extractionConfidence == null && !widget.doc.encrypted) {
      _reading = true;
      _pollForExtraction();
    }
  }

  /// (Re)fill the editable state from [doc]. Called once on init and again if
  /// polling lands a completed extraction.
  void _populate(TroveDocument doc) {
    _merchant.text = doc.merchant ?? '';
    _amount.text = doc.amount != null ? doc.amount!.toStringAsFixed(2) : '';
    _currency.text = doc.currency ?? 'INR';
    _category = doc.category;
    _docDate = doc.docDate;
    _dueDate = doc.dueDate;
    _warrantyUntil = _parseIso(doc.extra?['warrantyUntil']);
    _vital = doc.vital;
  }

  /// Poll the server for the extraction result. Stops when a fetched document has a
  /// non-null confidence (refilling the fields) or after [_maxPollAttempts]; either
  /// way the banner comes down. `mounted`/`_reading` guard against a disposed screen.
  Future<void> _pollForExtraction() async {
    final api = ref.read(documentsApiProvider);
    for (var i = 0; i < _maxPollAttempts; i++) {
      await Future<void>.delayed(_pollInterval);
      if (!mounted || !_reading) return;
      try {
        final fresh = await api.get(widget.doc.id);
        if (!mounted || !_reading) return;
        if (fresh.extractionConfidence != null) {
          setState(() {
            _doc = fresh;
            _populate(fresh);
            _reading = false;
          });
          return;
        }
      } catch (_) {
        // Transient read error: the client already toasts. Keep trying.
      }
    }
    // Never settled: drop the banner and leave the fields for manual entry.
    if (mounted) setState(() => _reading = false);
  }

  @override
  void dispose() {
    _merchant.dispose();
    _amount.dispose();
    _currency.dispose();
    super.dispose();
  }

  String? _iso(DateTime? d) => d?.toIso8601String().substring(0, 10);

  Future<void> _pickDate(bool due) async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: (due ? _dueDate : _docDate) ?? now,
      firstDate: DateTime(now.year - 20),
      lastDate: DateTime(now.year + 20),
    );
    if (picked != null) {
      setState(() => due ? _dueDate = picked : _docDate = picked);
    }
  }

  /// Set the warranty end date to N years from the document date (or today).
  void _setWarranty(int years) {
    final base = _docDate ?? DateTime.now();
    setState(() => _warrantyUntil = DateTime(base.year + years, base.month, base.day));
  }

  Future<void> _pickWarranty() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _warrantyUntil ?? now,
      firstDate: DateTime(now.year - 20),
      lastDate: DateTime(now.year + 30),
    );
    if (picked != null) setState(() => _warrantyUntil = picked);
  }

  Future<void> _confirm() async {
    setState(() => _busy = true);
    try {
      // Confirm replaces `extra` on the backend, so send the existing map plus the
      // warranty date (or drop the key when cleared) to preserve the extraction trail.
      final extra = {...?_doc.extra};
      if (_warrantyUntil != null) {
        extra['warrantyUntil'] = _iso(_warrantyUntil);
      } else {
        extra.remove('warrantyUntil');
      }
      await ref.read(documentsApiProvider).confirm(
            widget.doc.id,
            category: _category,
            merchant: _merchant.text.trim().isEmpty ? null : _merchant.text.trim(),
            amount: double.tryParse(_amount.text.trim()),
            currency: _currency.text.trim().isEmpty ? null : _currency.text.trim(),
            docDate: _iso(_docDate),
            dueDate: _iso(_dueDate),
            vital: _vital,
            extra: extra,
          );
      NoticeCenter.instance.show(Notice.local(
        level: NoticeLevel.success,
        code: 'CONFIRMED',
        userMessage: 'Saved to your vault.',
      ),);
      if (mounted) context.go('/home');
    } catch (_) {
      // toast already shown by the client
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final notice = _doc.extractionNotice;
    final categories = ref.watch(categoriesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Review & confirm')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          if (_reading) _readingBanner(scheme),
          const HelpCard(
            title: 'Confirming a document',
            user:
                "Check the details the AI pulled out and fix anything it misread (amounts and dates especially). Confirming is what makes a document count toward spend, become searchable, and generate reminders.",
            dev: null,
          ),
          if (notice != null)
            Container(
              margin: const EdgeInsets.only(bottom: 16),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: AppTheme.noticeColor(scheme, notice.level).withValues(alpha: 0.10),
                borderRadius: BorderRadius.circular(12),
                border: Border(
                    left: BorderSide(
                        color: AppTheme.noticeColor(scheme, notice.level), width: 3,),),
              ),
              child: Text(notice.userMessage),
            ),
          _anomalyBanner(scheme),
          categories.when(
            loading: () => const LinearProgressIndicator(),
            error: (_, __) => _categoryFallback(),
            data: (list) => _categoryField(list),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _merchant,
            decoration: const InputDecoration(
              labelText: 'Merchant',
              hintText: 'e.g. Reliance Energy',
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                flex: 2,
                child: TextField(
                  controller: _amount,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  inputFormatters: [
                    FilteringTextInputFormatter.allow(RegExp(r'[0-9.]')),
                  ],
                  decoration: const InputDecoration(
                    labelText: 'Amount',
                    hintText: 'e.g. 1299.00',
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: TextField(
                  controller: _currency,
                  textCapitalization: TextCapitalization.characters,
                  decoration: const InputDecoration(labelText: 'Currency'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(child: _dateField('Document date', _docDate, () => _pickDate(false))),
              const SizedBox(width: 12),
              Expanded(child: _dateField('Due date', _dueDate, () => _pickDate(true))),
            ],
          ),
          const SizedBox(height: 12),
          _dateField('Warranty until (optional)', _warrantyUntil, _pickWarranty),
          const SizedBox(height: 6),
          Wrap(
            spacing: 8,
            children: [
              OutlinedButton(onPressed: () => _setWarranty(1), child: const Text('+1 year')),
              OutlinedButton(onPressed: () => _setWarranty(2), child: const Text('+2 years')),
              if (_warrantyUntil != null)
                TextButton(
                  onPressed: () => setState(() => _warrantyUntil = null),
                  child: const Text('Clear'),
                ),
            ],
          ),
          Text(
            'For a purchase with a warranty (earbuds, a phone). Trove reminds you before it expires.',
            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
          ),
          if ((_doc.rawText ?? '').isNotEmpty) ...[
            const SizedBox(height: 8),
            ExpansionTile(
              tilePadding: EdgeInsets.zero,
              title: Text('What we read', style: TextStyle(color: scheme.onSurfaceVariant)),
              children: [
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text(_doc.rawText!,
                      style: const TextStyle(fontFamily: 'monospace', fontSize: 12),),
                ),
              ],
            ),
          ],
          const SizedBox(height: 8),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            value: _vital,
            onChanged: (v) => setState(() => _vital = v),
            title: const Text('Vital / sensitive'),
            subtitle: const Text('Encrypt at rest (passport, ID, policy)'),
          ),
          const SizedBox(height: 12),
          FilledButton(
            onPressed: _busy ? null : _confirm,
            child: _busy
                ? const SizedBox(
                    height: 20, width: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),)
                : const Text('Confirm'),
          ),
        ],
      ),
    );
  }

  /// A warn banner when this bill was flagged higher-than-usual for its category.
  Widget _anomalyBanner(ColorScheme scheme) {
    final a = _anomaly;
    if (a == null) return const SizedBox.shrink();
    final pct = (((a['deltaPct'] as num?) ?? 0) * 100).round();
    final avg = a['average'];
    final avgText = avg is num
        ? ' (you normally pay around ${avg.toStringAsFixed(2)} ${_doc.currency ?? ''})'.trimRight()
        : '';
    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppTheme.noticeColor(scheme, NoticeLevel.warning).withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(12),
        border: Border(
          left: BorderSide(
            color: AppTheme.noticeColor(scheme, NoticeLevel.warning), width: 3,),),
      ),
      child: Text(
        'This is about $pct% higher than usual for this category$avgText. Worth a second look before you confirm.',
      ),
    );
  }

  /// The "extraction still running" banner shown at the top while polling.
  Widget _readingBanner(ColorScheme scheme) => Container(
        margin: const EdgeInsets.only(bottom: 16),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: scheme.primary.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(12),
          border: Border(
            left: BorderSide(color: scheme.primary, width: 3),
          ),
        ),
        child: const Row(
          children: [
            SizedBox(
              height: 18,
              width: 18,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            SizedBox(width: 12),
            Expanded(
              child: Text(
                'Reading this with AI. The fields below will fill in automatically in a moment.',
              ),
            ),
          ],
        ),
      );

  /// Tap-to-pick category field: shows the current label and opens a bottom sheet
  /// with the full category list (nicer on mobile than a raw dropdown menu).
  Widget _categoryField(List<Category> list) {
    final match = list.where((c) => c.code == _category);
    final label =
        match.isNotEmpty ? match.first.label : (_category ?? 'Uncategorized');
    return InkWell(
      onTap: () => _pickCategory(list),
      child: InputDecorator(
        decoration: const InputDecoration(labelText: 'Category'),
        child: Row(
          children: [
            Expanded(child: Text(label)),
            const Icon(Icons.arrow_drop_down),
          ],
        ),
      ),
    );
  }

  /// Modal bottom sheet with a titled, scrollable list of categories.
  Future<void> _pickCategory(List<Category> list) async {
    final picked = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
              child: Text(
                'Choose a category',
                style: Theme.of(ctx).textTheme.titleMedium,
              ),
            ),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final c in list)
                    ListTile(
                      title: Text(c.label),
                      trailing:
                          c.code == _category ? const Icon(Icons.check) : null,
                      onTap: () => Navigator.of(ctx).pop(c.code),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
    if (picked != null) setState(() => _category = picked);
  }

  Widget _categoryFallback() => TextField(
        controller: TextEditingController(text: _category ?? ''),
        decoration: const InputDecoration(labelText: 'Category (code)'),
        onChanged: (v) => _category = v.trim().isEmpty ? null : v.trim(),
      );

  Widget _dateField(String label, DateTime? value, VoidCallback onTap) => InkWell(
        onTap: onTap,
        child: InputDecorator(
          decoration: InputDecoration(labelText: label),
          child: Text(value != null ? _iso(value)! : '-'),
        ),
      );
}
