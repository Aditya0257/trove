// Model sanity tests. The default scaffold widget test was removed because the app
// root needs a provider scope and async init; these unit tests are stable and fast.
import 'package:flutter_test/flutter_test.dart';
import 'package:trove/core/models/reminder.dart';
import 'package:trove/core/models/spend.dart';

void main() {
  test('Reminder.fromJson parses the lifecycle fields', () {
    final r = Reminder.fromJson({
      'id': 'r1',
      'spaceId': 's1',
      'type': 'renewal',
      'remindOn': '2026-08-01',
      'recurrence': 'monthly',
      'status': 'pending',
      'title': 'Rent - pay landlord',
    });
    expect(r.type, 'renewal');
    expect(r.repeats, isTrue);
    expect(r.isActive, isTrue);
    expect(r.displayTitle, 'Rent - pay landlord');
  });

  test('Reminder.displayTitle falls back to the type label', () {
    final r = Reminder.fromJson({
      'id': 'r2',
      'spaceId': 's1',
      'type': 'warranty_expiry',
      'remindOn': '2027-01-01',
      'status': 'pending',
    });
    expect(r.recurrence, 'none');
    expect(r.repeats, isFalse);
    expect(r.displayTitle, 'Warranty expiry');
  });

  test('SpendSummary.fromJson totals and categories', () {
    final s = SpendSummary.fromJson({
      'currency': 'INR',
      'total': 1234.5,
      'count': 3,
      'byCategory': [
        {'category': 'electricity', 'label': 'Electricity', 'total': 1000.0, 'count': 2},
        {'category': 'food', 'label': 'Food', 'total': 234.5, 'count': 1},
      ],
    });
    expect(s.currency, 'INR');
    expect(s.count, 3);
    expect(s.byCategory.length, 2);
    expect(s.byCategory.first.label, 'Electricity');
  });
}
