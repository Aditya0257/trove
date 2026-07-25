/// ============================================================================
///  spend_api - the spend summary for a space
/// ============================================================================
///  Purpose:  read /api/spend/summary for a space (total + per-category), for the
///            spend glance screen. Confirmed documents only, as on the backend.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/models/spend.dart';
import '../../core/providers.dart';

/// The spend summary for a space (total + per-category breakdown).
final spendSummaryProvider =
    FutureProvider.autoDispose.family<SpendSummary, String>((ref, spaceId) async {
  final data = await ref.read(apiClientProvider).get(
        '/api/spend/summary',
        query: {'spaceId': spaceId},
      ) as Map<String, dynamic>;
  return SpendSummary.fromJson(data);
});

/// The monthly spend series for a space (one entry per period).
final spendByMonthProvider =
    FutureProvider.autoDispose.family<List<MonthlySpend>, String>((ref, spaceId) async {
  final rows = await ref.read(apiClientProvider).get(
        '/api/spend/by-month',
        query: {'spaceId': spaceId, 'currency': 'INR', 'granularity': 'month'},
      ) as List<dynamic>;
  return rows
      .map((e) => MonthlySpend.fromJson((e as Map).cast<String, dynamic>()))
      .toList();
});
