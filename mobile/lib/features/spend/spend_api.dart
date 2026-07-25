/// ============================================================================
///  spend_api - the spend summary for a space
/// ============================================================================
///  Purpose:  read /api/spend/summary for a space (total + per-category), for the
///            spend glance screen. Confirmed documents only, as on the backend.
///
///  Time series: /api/spend/by-month accepts a `granularity` query of
///            "day" | "week" | "month" (defaults to month). The provider is
///            keyed by (spaceId, granularity) so switching granularity refetches
///            and caches each granularity independently.
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

/// Key for [spendByMonthProvider]: which space, at which granularity. A record
/// so the family caches (and refetches) each granularity independently while
/// still comparing by value.
typedef SpendSeriesKey = ({String spaceId, String granularity});

/// The time series for a space (one entry per period), at the requested
/// granularity ("day" | "week" | "month").
final spendByMonthProvider = FutureProvider.autoDispose
    .family<List<MonthlySpend>, SpendSeriesKey>((ref, key) async {
  final rows = await ref.read(apiClientProvider).get(
        '/api/spend/by-month',
        query: {
          'spaceId': key.spaceId,
          'currency': 'INR',
          'granularity': key.granularity,
        },
      ) as List<dynamic>;
  return rows
      .map((e) => MonthlySpend.fromJson((e as Map).cast<String, dynamic>()))
      .toList();
});
