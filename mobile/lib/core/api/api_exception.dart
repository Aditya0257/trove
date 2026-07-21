/// ============================================================================
///  ApiException — a failed API call, carrying its Notice (D23)
/// ============================================================================
///
///  Purpose
///  -------
///  Every failed request throws this. It always carries a `Notice` (parsed from the
///  server's envelope, or synthesized locally for network/timeout), so call sites can
///  react to `code` and screens can show the `userMessage` without reparsing.
///
///  Design
///  ------
///  `statusCode == 0` means the request never reached the server (offline/timeout).
/// ============================================================================
library;

import '../notice/notice.dart';

class ApiException implements Exception {
  const ApiException({
    required this.statusCode,
    required this.notice,
    this.requestId,
  });

  final int statusCode;
  final Notice notice;
  final String? requestId;

  bool get reachedServer => statusCode != 0;

  @override
  String toString() => 'ApiException($statusCode, ${notice.code}): ${notice.userMessage}';
}
