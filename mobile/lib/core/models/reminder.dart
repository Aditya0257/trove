/// Reminder — a scheduled nudge for a due/renewal/warranty date.
/// Mirrors backend ReminderResponse {id, documentId, spaceId, type, title, remindOn,
/// recurrence, status, completedAt, createdAt}.
library;

class Reminder {
  const Reminder({
    required this.id,
    required this.spaceId,
    required this.type,
    required this.remindOn,
    required this.status,
    this.title,
    this.recurrence = 'none',
    this.documentId,
    this.documentFilename,
    this.completedAt,
  });

  final String id;
  final String spaceId;
  final String type; // due | renewal | warranty_expiry
  final String? title; // optional human label, e.g. "Rent - pay landlord"
  final DateTime remindOn;
  final String recurrence; // none | weekly | monthly | quarterly | yearly
  final String status; // pending | sent | dismissed | done
  final String? documentId;
  final String? documentFilename; // linked file name, resolved server-side
  final DateTime? completedAt;

  bool get isPending => status == 'pending';
  bool get isActive => status == 'pending' || status == 'sent';
  bool get repeats => recurrence != 'none';

  String get label => switch (type) {
        'renewal' => 'Renewal',
        'warranty_expiry' => 'Warranty expiry',
        _ => 'Payment due',
      };

  /// What to show as the primary line: the user's title, else the type label.
  String get displayTitle => (title != null && title!.isNotEmpty) ? title! : label;

  factory Reminder.fromJson(Map<String, dynamic> json) => Reminder(
        id: json['id'] as String,
        spaceId: json['spaceId'] as String,
        type: (json['type'] as String?) ?? 'due',
        title: json['title'] as String?,
        // Tolerant parse: a malformed/absent remindOn must not throw and break the whole
        // reminders list (mirrors how completedAt below is guarded).
        remindOn: DateTime.tryParse(json['remindOn']?.toString() ?? '') ?? DateTime.now(),
        recurrence: (json['recurrence'] as String?) ?? 'none',
        status: (json['status'] as String?) ?? 'pending',
        documentId: json['documentId'] as String?,
        documentFilename: json['documentFilename'] as String?,
        completedAt: (json['completedAt'] is String)
            ? DateTime.tryParse(json['completedAt'] as String)
            : null,
    );
}
