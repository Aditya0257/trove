/// ============================================================================
///  insights_api - document intelligence providers (expiring soon, recurring)
/// ============================================================================
///  Read-only FutureProviders over the /api/insights endpoints, keyed by space
///  (and, for expiring, the chosen day window). Mirrors the spend providers.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/models/insights.dart';
import '../../core/providers.dart';

/// Expiring is keyed by (space, window) so changing the window refetches.
typedef ExpiringKey = ({String spaceId, int withinDays});

final expiringProvider =
    FutureProvider.autoDispose.family<List<ExpiringItem>, ExpiringKey>((ref, key) async {
  final data = await ref.read(apiClientProvider).get(
        '/api/insights/expiring',
        query: {'spaceId': key.spaceId, 'withinDays': key.withinDays},
      ) as List<dynamic>;
  return data
      .map((e) => ExpiringItem.fromJson((e as Map).cast<String, dynamic>()))
      .toList();
});

final recurringProvider =
    FutureProvider.autoDispose.family<List<RecurringGroup>, String>((ref, spaceId) async {
  final data = await ref.read(apiClientProvider).get(
        '/api/insights/recurring',
        query: {'spaceId': spaceId},
      ) as List<dynamic>;
  return data
      .map((e) => RecurringGroup.fromJson((e as Map).cast<String, dynamic>()))
      .toList();
});
