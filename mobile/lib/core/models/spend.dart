/// SpendSummary - total plus per-category breakdown for a space over a range.
/// Mirrors the backend /api/spend/summary response.
library;

class CategorySpend {
  const CategorySpend({
    required this.category,
    required this.label,
    required this.total,
    required this.count,
  });

  final String category;
  final String label;
  final double total;
  final int count;

  factory CategorySpend.fromJson(Map<String, dynamic> j) => CategorySpend(
        category: (j['category'] as String?) ?? '',
        label: (j['label'] as String?) ?? (j['category'] as String?) ?? 'Other',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        count: (j['count'] as num?)?.toInt() ?? 0,
      );
}

class MonthlySpend {
  const MonthlySpend({
    required this.period,
    required this.total,
    required this.count,
  });

  final String period;
  final double total;
  final int count;

  factory MonthlySpend.fromJson(Map<String, dynamic> j) => MonthlySpend(
        period: (j['period'] as String?) ?? '',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        count: (j['count'] as num?)?.toInt() ?? 0,
      );
}

class SpendSummary {
  const SpendSummary({
    required this.currency,
    required this.total,
    required this.count,
    required this.byCategory,
  });

  final String currency;
  final double total;
  final int count;
  final List<CategorySpend> byCategory;

  factory SpendSummary.fromJson(Map<String, dynamic> j) => SpendSummary(
        currency: (j['currency'] as String?) ?? 'INR',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        count: (j['count'] as num?)?.toInt() ?? 0,
        byCategory: ((j['byCategory'] as List<dynamic>?) ?? const [])
            .map((e) => CategorySpend.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
      );
}
