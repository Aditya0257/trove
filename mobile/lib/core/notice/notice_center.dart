/// ============================================================================
///  NoticeCenter — the app-wide toast queue (D23)
/// ============================================================================
///
///  Purpose
///  -------
///  A single place anything can push a Notice to be shown as a two-channel toast.
///  The root widget listens and renders; callers (API client, controllers, screens)
///  never touch UI directly — they just `NoticeCenter.instance.show(notice)`.
///
///  Business use case
///  -----------------
///  Consistent, everywhere-the-same feedback. A quota fallback surfaced by the API
///  interceptor and a validation error surfaced by a form use the exact same path.
///
///  Design
///  ------
///  ChangeNotifier singleton holding the latest notice + a monotonically increasing
///  token so the root can distinguish "same text, shown again". UI decides styling
///  and dismissal; this is pure transport.
/// ============================================================================
library;

import 'package:flutter/foundation.dart';

import 'notice.dart';

class NoticeCenter extends ChangeNotifier {
  NoticeCenter._();
  static final NoticeCenter instance = NoticeCenter._();

  Notice? _latest;
  int _token = 0;

  Notice? get latest => _latest;
  int get token => _token;

  void show(Notice notice) {
    _latest = notice;
    _token++;
    notifyListeners();
  }
}
