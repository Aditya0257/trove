/// ============================================================================
///  reminders_api — list and dismiss reminders
/// ============================================================================
///  Purpose:  read a space's reminders and dismiss them, over the notice-aware
///            ApiClient. Feeds both the reminders screen and the on-device scheduler.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/models/reminder.dart';
import '../../core/providers.dart';

class RemindersApi {
  RemindersApi(this._ref);
  final Ref _ref;

  Future<List<Reminder>> list(String spaceId, {String? status}) async {
    final data = await _ref.read(apiClientProvider).get('/api/reminders', query: {
      'spaceId': spaceId,
      if (status != null) 'status': status,
    },) as List<dynamic>;
    return data
        .map((e) => Reminder.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  Future<void> dismiss(String id) async {
    await _ref.read(apiClientProvider).post('/api/reminders/$id/dismiss');
  }

  /// Mark handled (the backend schedules the next occurrence if it repeats).
  Future<void> markDone(String id) async {
    await _ref.read(apiClientProvider).post('/api/reminders/$id/done');
  }

  /// Push the reminder out by [days] from today (back to pending).
  Future<void> snooze(String id, int days) async {
    await _ref.read(apiClientProvider).post('/api/reminders/$id/snooze', query: {'days': days});
  }

  /// Create a reminder in a space.
  Future<void> create({
    required String spaceId,
    required String type,
    String? title,
    required DateTime remindOn,
    String recurrence = 'none',
  }) async {
    await _ref.read(apiClientProvider).post('/api/reminders', query: {'spaceId': spaceId}, body: {
      'type': type,
      if (title != null && title.trim().isNotEmpty) 'title': title.trim(),
      'remindOn': _iso(remindOn),
      'recurrence': recurrence,
    },);
  }

  /// Edit an existing reminder.
  Future<void> update(
    String id, {
    required String type,
    String? title,
    required DateTime remindOn,
    String recurrence = 'none',
  }) async {
    await _ref.read(apiClientProvider).patch('/api/reminders/$id', body: {
      'type': type,
      if (title != null && title.trim().isNotEmpty) 'title': title.trim(),
      'remindOn': _iso(remindOn),
      'recurrence': recurrence,
    },);
  }

  static String _iso(DateTime d) => d.toIso8601String().substring(0, 10);
}

final remindersApiProvider = Provider<RemindersApi>((ref) => RemindersApi(ref));

/// Pending reminders for a space, soonest first.
final remindersProvider =
    FutureProvider.autoDispose.family<List<Reminder>, String>((ref, spaceId) async {
  final list = await ref.watch(remindersApiProvider).list(spaceId, status: 'pending');
  list.sort((a, b) => a.remindOn.compareTo(b.remindOn));
  return list;
});
