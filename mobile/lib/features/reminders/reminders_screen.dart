/// ============================================================================
///  RemindersScreen — upcoming nudges for a space
/// ============================================================================
///
///  Purpose
///  -------
///  Lists a space's pending reminders (soonest first), lets the user dismiss one, and
///  — as a side effect of loading — schedules the on-device popups so the 7/1/0-day
///  warranty/renewal/bill alerts fire even when the app is closed.
///
///  Design
///  ------
///  Reads `remindersProvider`; a `ref.listen` hands the freshest list to the
///  NotificationService to (re)schedule. Dismiss refreshes the list.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/models/reminder.dart';
import '../../core/notifications/notification_service.dart';
import 'reminders_api.dart';

class RemindersScreen extends ConsumerWidget {
  const RemindersScreen({super.key, required this.spaceId});
  final String spaceId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final reminders = ref.watch(remindersProvider(spaceId));

    // Whenever the list resolves, mirror it into on-device scheduled notifications.
    ref.listen(remindersProvider(spaceId), (_, next) {
      final list = next.asData?.value;
      if (list != null) {
        NotificationService.instance.syncReminders(list);
      }
    });

    return Scaffold(
      appBar: AppBar(title: const Text('Reminders')),
      body: RefreshIndicator(
        onRefresh: () => ref.refresh(remindersProvider(spaceId).future),
        child: reminders.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, __) => ListView(children: [
            const SizedBox(height: 80),
            Center(
              child: Text('Couldn\'t load reminders. Pull to retry.',
                  style: TextStyle(color: scheme.onSurfaceVariant),),
            ),
          ],),
          data: (list) => list.isEmpty
              ? ListView(children: [
                  const SizedBox(height: 80),
                  Center(
                    child: Text('No upcoming reminders.',
                        style: TextStyle(color: scheme.onSurfaceVariant),),
                  ),
                ],)
              : ListView.separated(
                  itemCount: list.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (_, i) {
                    final r = list[i];
                    Future<void> act(Future<void> Function() op) async {
                      await op();
                      ref.invalidate(remindersProvider(spaceId));
                    }
                    return _ReminderTile(
                      reminder: r,
                      onDone: () => act(() => ref.read(remindersApiProvider).markDone(r.id)),
                      onSnooze: (d) => act(() => ref.read(remindersApiProvider).snooze(r.id, d)),
                      onDismiss: () => act(() => ref.read(remindersApiProvider).dismiss(r.id)),
                    );
                  },
                ),
        ),
      ),
    );
  }
}

class _ReminderTile extends StatelessWidget {
  const _ReminderTile({
    required this.reminder,
    required this.onDone,
    required this.onSnooze,
    required this.onDismiss,
  });
  final Reminder reminder;
  final Future<void> Function() onDone;
  final void Function(int days) onSnooze;
  final Future<void> Function() onDismiss;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final icon = switch (reminder.type) {
      'warranty_expiry' => Icons.verified_user_outlined,
      'renewal' => Icons.autorenew,
      _ => Icons.event_outlined,
    };
    final days = reminder.remindOn.difference(DateTime.now()).inDays;
    final when = days > 1
        ? 'in $days days'
        : days == 1
            ? 'tomorrow'
            : days == 0
                ? 'today'
                : 'overdue';
    final date = reminder.remindOn.toIso8601String().substring(0, 10);
    final repeat = reminder.repeats
        ? ' · repeats ${reminder.recurrence}'
        : '';

    return ListTile(
      leading: Icon(icon),
      title: Text(reminder.displayTitle),
      subtitle: Text('${reminder.label} · $when · $date$repeat'),
      trailing: PopupMenuButton<String>(
        onSelected: (v) {
          switch (v) {
            case 'done':
              onDone();
            case 'snooze1':
              onSnooze(1);
            case 'snooze3':
              onSnooze(3);
            case 'snooze7':
              onSnooze(7);
            case 'dismiss':
              onDismiss();
          }
        },
        itemBuilder: (_) => [
          const PopupMenuItem(value: 'done', child: Text('Done')),
          const PopupMenuItem(value: 'snooze1', child: Text('Snooze 1 day')),
          const PopupMenuItem(value: 'snooze3', child: Text('Snooze 3 days')),
          const PopupMenuItem(value: 'snooze7', child: Text('Snooze 1 week')),
          PopupMenuItem(
            value: 'dismiss',
            child: Text('Dismiss', style: TextStyle(color: scheme.error)),
          ),
        ],
      ),
    );
  }
}
