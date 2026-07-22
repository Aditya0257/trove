/// ============================================================================
///  NotificationService — on-device reminder popups (free, no server)
/// ============================================================================
///
///  Purpose
///  -------
///  Turns the backend's reminders (already spaced at 7/1/0 days before a due date)
///  into scheduled local notifications on the phone, so a warranty/renewal/bill
///  surfaces as a popup even without opening the app — at no cost and no server.
///
///  Business use case
///  -----------------
///  The "remind me a week before the warranty expires" experience, delivered locally.
///  Email (server-side) is the always-on channel; this is the on-device nudge.
///
///  Solution architecture
///  ---------------------
///  `flutter_local_notifications` + `timezone` for zoned scheduling. `syncReminders`
///  clears and re-schedules from the current pending reminders, so it's idempotent and
///  safe to call whenever reminders load. Uses inexact scheduling to avoid the
///  restricted exact-alarm permission — a few minutes' drift is fine for reminders.
///
///  Reasoning & logic
///  -----------------
///  Notifications fire at 09:00 local on each reminder's date; past dates are skipped.
///  Requires the native setup documented in mobile/README.md (permissions + channel).
/// ============================================================================
library;

import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_timezone/flutter_timezone.dart';
import 'package:timezone/data/latest.dart' as tzdata;
import 'package:timezone/timezone.dart' as tz;

import '../models/reminder.dart';

class NotificationService {
  NotificationService._();
  static final NotificationService instance = NotificationService._();

  final FlutterLocalNotificationsPlugin _plugin = FlutterLocalNotificationsPlugin();
  bool _ready = false;

  static const _details = NotificationDetails(
    android: AndroidNotificationDetails(
      'trove_reminders',
      'Reminders',
      channelDescription: 'Warranty, renewal and bill reminders',
      importance: Importance.max,
      priority: Priority.high,
    ),
    iOS: DarwinNotificationDetails(),
  );

  /// One-time init: timezone data, plugin, and OS permission prompts.
  Future<void> init() async {
    tzdata.initializeTimeZones();
    try {
      tz.setLocalLocation(tz.getLocation(await FlutterTimezone.getLocalTimezone()));
    } catch (_) {
      // Leave tz.local at its default (UTC) if the device zone can't be resolved.
    }
    await _plugin.initialize(const InitializationSettings(
      android: AndroidInitializationSettings('@mipmap/ic_launcher'),
      iOS: DarwinInitializationSettings(),
    ),);
    await _plugin
        .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
        ?.requestNotificationsPermission();
    await _plugin
        .resolvePlatformSpecificImplementation<IOSFlutterLocalNotificationsPlugin>()
        ?.requestPermissions(alert: true, badge: true, sound: true);
    _ready = true;
  }

  /// Replaces all scheduled reminder popups with ones derived from `reminders`
  /// (pending + still in the future). Idempotent.
  Future<void> syncReminders(List<Reminder> reminders) async {
    if (!_ready) return;
    await _plugin.cancelAll();
    final now = tz.TZDateTime.now(tz.local);
    for (final r in reminders) {
      if (!r.isPending) continue;
      final when = tz.TZDateTime(
        tz.local,
        r.remindOn.year,
        r.remindOn.month,
        r.remindOn.day,
        9, // 09:00 local
      );
      if (!when.isAfter(now)) continue;
      await _plugin.zonedSchedule(
        r.id.hashCode & 0x7fffffff,
        'Trove: ${r.label.toLowerCase()} coming up',
        'A ${r.label.toLowerCase()} needs your attention. Open Trove to review.',
        when,
        _details,
        androidScheduleMode: AndroidScheduleMode.inexactAllowWhileIdle,
        uiLocalNotificationDateInterpretation:
            UILocalNotificationDateInterpretation.absoluteTime,
      );
    }
  }
}
