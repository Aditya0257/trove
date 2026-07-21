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
                  itemBuilder: (_, i) => _ReminderTile(
                    reminder: list[i],
                    onDismiss: () async {
                      await ref.read(remindersApiProvider).dismiss(list[i].id);
                      ref.invalidate(remindersProvider(spaceId));
                    },
                  ),
                ),
        ),
      ),
    );
  }
}

class _ReminderTile extends StatelessWidget {
  const _ReminderTile({required this.reminder, required this.onDismiss});
  final Reminder reminder;
  final Future<void> Function() onDismiss;

  @override
  Widget build(BuildContext context) {
    final icon = switch (reminder.type) {
      'warranty_expiry' => Icons.verified_user_outlined,
      'renewal' => Icons.autorenew,
      _ => Icons.event_outlined,
    };
    final days = reminder.remindOn
        .difference(DateTime.now())
        .inDays;
    final when = days > 1
        ? 'in $days days'
        : days == 1
            ? 'tomorrow'
            : days == 0
                ? 'today'
                : 'overdue';

    return ListTile(
      leading: Icon(icon),
      title: Text('${reminder.label} · $when'),
      subtitle: Text(reminder.remindOn.toIso8601String().substring(0, 10)),
      trailing: TextButton(onPressed: onDismiss, child: const Text('Dismiss')),
    );
  }
}
