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
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _openForm(context, ref),
        icon: const Icon(Icons.add),
        label: const Text('New reminder'),
      ),
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
                      onEdit: () => _openForm(context, ref, existing: r),
                    );
                  },
                ),
        ),
      ),
    );
  }

  /// Opens the create/edit sheet, then creates or updates and refreshes the list.
  Future<void> _openForm(BuildContext context, WidgetRef ref, {Reminder? existing}) async {
    final res = await showModalBottomSheet<_FormResult>(
      context: context,
      isScrollControlled: true,
      builder: (_) => _ReminderFormSheet(existing: existing),
    );
    if (res == null) return;
    final api = ref.read(remindersApiProvider);
    if (existing == null) {
      await api.create(spaceId: spaceId, type: res.type, title: res.title, remindOn: res.remindOn, recurrence: res.recurrence);
    } else {
      await api.update(existing.id, type: res.type, title: res.title, remindOn: res.remindOn, recurrence: res.recurrence);
    }
    ref.invalidate(remindersProvider(spaceId));
  }
}

class _ReminderTile extends StatelessWidget {
  const _ReminderTile({
    required this.reminder,
    required this.onDone,
    required this.onSnooze,
    required this.onDismiss,
    required this.onEdit,
  });
  final Reminder reminder;
  final Future<void> Function() onDone;
  final void Function(int days) onSnooze;
  final Future<void> Function() onDismiss;
  final VoidCallback onEdit;

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
            case 'edit':
              onEdit();
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
          const PopupMenuItem(value: 'edit', child: Text('Edit')),
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

/// The values collected by the create/edit sheet.
class _FormResult {
  _FormResult(this.type, this.title, this.remindOn, this.recurrence);
  final String type;
  final String? title;
  final DateTime remindOn;
  final String recurrence;
}

/// A bottom-sheet form for creating or editing a reminder.
class _ReminderFormSheet extends StatefulWidget {
  const _ReminderFormSheet({this.existing});
  final Reminder? existing;
  @override
  State<_ReminderFormSheet> createState() => _ReminderFormSheetState();
}

class _ReminderFormSheetState extends State<_ReminderFormSheet> {
  late final TextEditingController _title =
      TextEditingController(text: widget.existing?.title ?? '');
  late String _type = widget.existing?.type ?? 'due';
  late String _recurrence = widget.existing?.recurrence ?? 'none';
  late DateTime _remindOn = widget.existing?.remindOn ?? DateTime.now();

  static const _types = {
    'due': 'Payment due',
    'renewal': 'Renewal',
    'warranty_expiry': 'Warranty expiry',
  };
  static const _recurrences = {
    'none': 'Does not repeat',
    'weekly': 'Weekly',
    'monthly': 'Monthly',
    'quarterly': 'Quarterly',
    'yearly': 'Yearly',
  };

  @override
  void dispose() {
    _title.dispose();
    super.dispose();
  }

  Future<void> _pickDate() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _remindOn,
      firstDate: DateTime(now.year - 5),
      lastDate: DateTime(now.year + 30),
    );
    if (picked != null) setState(() => _remindOn = picked);
  }

  @override
  Widget build(BuildContext context) {
    final iso = _remindOn.toIso8601String().substring(0, 10);
    return Padding(
      padding: EdgeInsets.only(
        left: 20, right: 20, top: 18,
        bottom: MediaQuery.of(context).viewInsets.bottom + 20,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(widget.existing == null ? 'New reminder' : 'Edit reminder',
              style: Theme.of(context).textTheme.titleLarge,),
          const SizedBox(height: 16),
          TextField(
            controller: _title,
            decoration: const InputDecoration(
              labelText: 'Title (optional)',
              hintText: 'e.g. Rent - pay landlord',
            ),
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<String>(
            initialValue: _type,
            decoration: const InputDecoration(labelText: 'Type'),
            items: [
              for (final e in _types.entries)
                DropdownMenuItem(value: e.key, child: Text(e.value)),
            ],
            onChanged: (v) => setState(() => _type = v ?? _type),
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<String>(
            initialValue: _recurrence,
            decoration: const InputDecoration(labelText: 'Repeat'),
            items: [
              for (final e in _recurrences.entries)
                DropdownMenuItem(value: e.key, child: Text(e.value)),
            ],
            onChanged: (v) => setState(() => _recurrence = v ?? _recurrence),
          ),
          const SizedBox(height: 12),
          InkWell(
            onTap: _pickDate,
            child: InputDecorator(
              decoration: const InputDecoration(labelText: 'Remind on'),
              child: Text(iso),
            ),
          ),
          const SizedBox(height: 20),
          FilledButton(
            onPressed: () => Navigator.pop(
              context,
              _FormResult(_type, _title.text, _remindOn, _recurrence),
            ),
            child: Text(widget.existing == null ? 'Add reminder' : 'Save changes'),
          ),
          const SizedBox(height: 4),
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        ],
      ),
    );
  }
}
