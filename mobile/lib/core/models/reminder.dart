/// Reminder — a scheduled nudge for a due/renewal/warranty date.
/// Mirrors backend ReminderResponse {id, documentId, spaceId, type, remindOn, status, createdAt}.
library;

class Reminder {
  const Reminder({
    required this.id,
    required this.spaceId,
    required this.type,
    required this.remindOn,
    required this.status,
    this.documentId,
  });

  final String id;
  final String spaceId;
  final String type; // due | renewal | warranty_expiry
  final DateTime remindOn;
  final String status; // pending | sent | dismissed
  final String? documentId;

  bool get isPending => status == 'pending';

  String get label => switch (type) {
        'renewal' => 'Renewal',
        'warranty_expiry' => 'Warranty',
        _ => 'Payment',
      };

  factory Reminder.fromJson(Map<String, dynamic> json) => Reminder(
        id: json['id'] as String,
        spaceId: json['spaceId'] as String,
        type: (json['type'] as String?) ?? 'due',
        remindOn: DateTime.parse(json['remindOn'] as String),
        status: (json['status'] as String?) ?? 'pending',
        documentId: json['documentId'] as String?,
    );
}
