/// ============================================================================
///  Insights models - document intelligence DTOs (mirror com.trove.insights)
/// ============================================================================
///  ExpiringItem: one upcoming/just-passed thing to act on (a bill due, a renewal,
///  a warranty end). RecurringGroup: a merchant+category that recurs on a regular
///  cadence, with the predicted next date. Tolerant parsing like the other models.
/// ============================================================================
library;

class ExpiringItem {
  const ExpiringItem({
    required this.documentId,
    required this.title,
    required this.kind,
    required this.date,
    required this.daysLeft,
    this.category,
    this.amount,
    this.currency,
  });

  final String documentId;
  final String title;
  final String? category;
  final String kind; // due | renewal | warranty
  final DateTime date;
  final int daysLeft; // negative = already overdue
  final double? amount;
  final String? currency;

  static double? _num(dynamic v) {
    if (v == null) return null;
    if (v is num) return v.toDouble();
    return double.tryParse(v.toString());
  }

  factory ExpiringItem.fromJson(Map<String, dynamic> json) => ExpiringItem(
        documentId: json['documentId'] as String,
        title: (json['title'] as String?) ?? 'Document',
        category: json['category'] as String?,
        kind: (json['kind'] as String?) ?? 'due',
        date: DateTime.tryParse(json['date']?.toString() ?? '') ?? DateTime.now(),
        daysLeft: (json['daysLeft'] as num?)?.toInt() ?? 0,
        amount: _num(json['amount']),
        currency: json['currency'] as String?,
      );
}

class RecurringGroup {
  const RecurringGroup({
    required this.occurrences,
    required this.cadence,
    this.merchant,
    this.category,
    this.categoryLabel,
    this.averageAmount,
    this.currency,
    this.lastSeen,
    this.nextExpected,
  });

  final String? merchant;
  final String? category;
  final String? categoryLabel;
  final int occurrences;
  final String cadence; // weekly | monthly | quarterly | yearly
  final double? averageAmount;
  final String? currency;
  final DateTime? lastSeen;
  final DateTime? nextExpected;

  static DateTime? _date(dynamic v) =>
      (v is String && v.isNotEmpty) ? DateTime.tryParse(v) : null;

  factory RecurringGroup.fromJson(Map<String, dynamic> json) => RecurringGroup(
        merchant: json['merchant'] as String?,
        category: json['category'] as String?,
        categoryLabel: json['categoryLabel'] as String?,
        occurrences: (json['occurrences'] as num?)?.toInt() ?? 0,
        cadence: (json['cadence'] as String?) ?? 'monthly',
        averageAmount: ExpiringItem._num(json['averageAmount']),
        currency: json['currency'] as String?,
        lastSeen: _date(json['lastSeen']),
        nextExpected: _date(json['nextExpected']),
      );
}
