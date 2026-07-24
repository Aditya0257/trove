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

import '../../core/models/document.dart';
import '../../core/notice/notice.dart';
import '../../core/notice/notice_center.dart';
import '../../ui/theme.dart';
import 'documents_api.dart';

class ConfirmScreen extends ConsumerStatefulWidget {
  const ConfirmScreen({super.key, required this.doc});
  final TroveDocument doc;

  @override
  ConsumerState<ConfirmScreen> createState() => _ConfirmScreenState();
}

class _ConfirmScreenState extends ConsumerState<ConfirmScreen> {
  late final TextEditingController _merchant =
      TextEditingController(text: widget.doc.merchant ?? '');
  late final TextEditingController _amount = TextEditingController(
      text: widget.doc.amount != null ? widget.doc.amount!.toStringAsFixed(2) : '',);
  late final TextEditingController _currency =
      TextEditingController(text: widget.doc.currency ?? 'INR');
  late String? _category = widget.doc.category;
  late DateTime? _docDate = widget.doc.docDate;
  late DateTime? _dueDate = widget.doc.dueDate;
  late DateTime? _warrantyUntil = _parseIso(widget.doc.extra?['warrantyUntil']);
  late bool _vital = widget.doc.vital;
  bool _busy = false;

  static DateTime? _parseIso(Object? v) =>
      (v is String && v.isNotEmpty) ? DateTime.tryParse(v) : null;

  /// The anomaly verdict stored on the document at its last confirm, if flagged.
  Map<String, dynamic>? get _anomaly {
    final a = widget.doc.extra?['anomaly'];
    return (a is Map && a['anomaly'] == true) ? a.cast<String, dynamic>() : null;
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
      final extra = {...?widget.doc.extra};
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
    final notice = widget.doc.extractionNotice;
    final categories = ref.watch(categoriesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Review & confirm')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
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
            data: (list) => DropdownButtonFormField<String>(
              initialValue:
                  list.any((c) => c.code == _category) ? _category : null,
              decoration: const InputDecoration(labelText: 'Category'),
              items: [
                for (final c in list)
                  DropdownMenuItem(value: c.code, child: Text(c.label)),
              ],
              onChanged: (v) => setState(() => _category = v),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _merchant,
            decoration: const InputDecoration(labelText: 'Merchant'),
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
                  decoration: const InputDecoration(labelText: 'Amount'),
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
          if ((widget.doc.rawText ?? '').isNotEmpty) ...[
            const SizedBox(height: 8),
            ExpansionTile(
              tilePadding: EdgeInsets.zero,
              title: Text('What we read', style: TextStyle(color: scheme.onSurfaceVariant)),
              children: [
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text(widget.doc.rawText!,
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
        ? ' (you normally pay around ${avg.toStringAsFixed(2)} ${widget.doc.currency ?? ''})'.trimRight()
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
