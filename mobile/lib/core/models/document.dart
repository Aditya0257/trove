/// ============================================================================
///  TroveDocument — a stored document + its extraction (mirrors DocumentResponse)
/// ============================================================================
///
///  Purpose
///  -------
///  The client view of a document: identity, category/merchant/amount/dates, review
///  status, and the extraction `extra` (including the Notice System's extractionMeta).
///
///  Design
///  ------
///  Tolerant parsing (numbers may arrive as num or string). `extractionMeta`/`notice`
///  are surfaced as typed getters so the confirm screen and Developer drawer can show
///  "why it read this / why it couldn't".
/// ============================================================================
library;

import '../notice/notice.dart';

class TroveDocument {
  const TroveDocument({
    required this.id,
    required this.spaceId,
    required this.status,
    this.category,
    this.merchant,
    this.docDate,
    this.amount,
    this.currency,
    this.dueDate,
    this.rawText,
    this.vital = false,
    this.encrypted = false,
    this.fileUrl,
    this.extractionConfidence,
    this.extra,
  });

  final String id;
  final String spaceId;
  final String status; // needs_review | confirmed
  final String? category;
  final String? merchant;
  final DateTime? docDate;
  final double? amount;
  final String? currency;
  final DateTime? dueDate;
  final String? rawText;
  final bool vital;
  final bool encrypted;
  final String? fileUrl;
  final double? extractionConfidence;
  final Map<String, dynamic>? extra;

  bool get needsReview => status == 'needs_review';

  Map<String, dynamic>? get extractionMeta {
    final m = extra?['extractionMeta'];
    return m is Map ? m.cast<String, dynamic>() : null;
  }

  /// The extraction outcome as a Notice, if present (EXTRACTION_OK / _QUOTA / …).
  Notice? get extractionNotice {
    final n = extractionMeta?['notice'];
    return n is Map ? Notice.fromJson(n.cast<String, dynamic>()) : null;
  }

  static double? _num(dynamic v) {
    if (v == null) return null;
    if (v is num) return v.toDouble();
    return double.tryParse(v.toString());
  }

  static DateTime? _date(dynamic v) =>
      v == null ? null : DateTime.tryParse(v.toString());

  factory TroveDocument.fromJson(Map<String, dynamic> json) => TroveDocument(
        id: json['id'] as String,
        spaceId: json['spaceId'] as String,
        status: (json['status'] as String?) ?? 'needs_review',
        category: json['category'] as String?,
        merchant: json['merchant'] as String?,
        docDate: _date(json['docDate']),
        amount: _num(json['amount']),
        currency: json['currency'] as String?,
        dueDate: _date(json['dueDate']),
        rawText: json['rawText'] as String?,
        vital: json['vital'] == true,
        encrypted: json['encrypted'] == true,
        fileUrl: json['fileUrl'] as String?,
        extractionConfidence: _num(json['extractionConfidence']),
        extra: (json['extra'] as Map?)?.cast<String, dynamic>(),
    );
}
