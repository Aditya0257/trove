/// ============================================================================
///  Notice — the two-channel user+developer message (mirrors backend ApiNotice, D23)
/// ============================================================================
///
///  Purpose
///  -------
///  The client-side twin of the backend `ApiNotice`. Carries a calm `userMessage`
///  and a precise `devNote` for the same event, plus a `level`, machine `code`, and
///  free-form `meta`. Every toast and every Developer-drawer row is a Notice.
///
///  Business use case
///  -----------------
///  Trove's UX principle (D23): dignify outcomes, don't hide them. The user sees the
///  friendly line; the curious/developer expands the note. Same contract as the web
///  client, so behaviour is identical across platforms.
///
///  Design
///  ------
///  Immutable value type with a tolerant `fromJson` (the server sends lowercase
///  levels). `NoticeLevel` also has client-only synthesis (e.g. a network error the
///  server never saw) so the UI has one uniform type to render.
/// ============================================================================
library;

enum NoticeLevel {
  info,
  success,
  warning,
  error;

  static NoticeLevel from(String? s) {
    switch ((s ?? '').toLowerCase()) {
      case 'success':
        return NoticeLevel.success;
      case 'warning':
        return NoticeLevel.warning;
      case 'error':
        return NoticeLevel.error;
      default:
        return NoticeLevel.info;
    }
  }
}

class Notice {
  const Notice({
    required this.level,
    required this.code,
    required this.userMessage,
    this.devNote,
    this.meta,
  });

  final NoticeLevel level;
  final String code;
  final String userMessage;
  final String? devNote;
  final Map<String, dynamic>? meta;

  factory Notice.fromJson(Map<String, dynamic> json) => Notice(
        level: NoticeLevel.from(json['level'] as String?),
        code: (json['code'] as String?) ?? 'UNKNOWN',
        userMessage:
            (json['userMessage'] as String?) ?? 'Something happened.',
        devNote: json['devNote'] as String?,
        meta: (json['meta'] as Map?)?.cast<String, dynamic>(),
    );

  /// A client-side notice for something the server never reported (e.g. offline).
  factory Notice.local({
    required NoticeLevel level,
    required String code,
    required String userMessage,
    String? devNote,
  }) =>
      Notice(
        level: level,
        code: code,
        userMessage: userMessage,
        devNote: devNote,
      );
}
