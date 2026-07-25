/// ============================================================================
///  DeveloperLog — the in-app Developer surface's data source (D23)
/// ============================================================================
///
///  Purpose
///  -------
///  A bounded, in-memory ring of every API call the app made: method, path, status,
///  client-measured duration, the server request-id, and any notice/extraction meta.
///  The Developer drawer renders this; nothing here is secret.
///
///  Business use case
///  -----------------
///  The user explicitly wants "lots of info, presented in a clean, senior way" — the
///  drawer is that, on mobile, and this is its store. Great for debugging on a real
///  device where there's no browser console.
///
///  Design
///  ------
///  A ChangeNotifier singleton (so any widget can `ListenableBuilder` on it without
///  coupling to Riverpod), capped at AppConfig.devLogCapacity, newest-first.
/// ============================================================================
library;

import 'package:flutter/foundation.dart';

import '../config.dart';
import 'notice.dart';

@immutable
class DevLogEntry {
  const DevLogEntry({
    required this.at,
    required this.method,
    required this.path,
    required this.statusCode,
    required this.durationMs,
    this.requestId,
    this.notice,
    this.extractionMeta,
    this.body,
  });

  final DateTime at;
  final String method;
  final String path;
  final int statusCode; // 0 = never reached the server (network/timeout)
  final int durationMs;
  final String? requestId;
  final Notice? notice;
  final Map<String, dynamic>? extractionMeta;
  final Object? body; // the response body (size-capped), for the Developer drawer

  bool get ok => statusCode >= 200 && statusCode < 300;
  bool get reachedServer => statusCode != 0;
}

class DeveloperLog extends ChangeNotifier {
  DeveloperLog._();
  static final DeveloperLog instance = DeveloperLog._();

  final List<DevLogEntry> _entries = <DevLogEntry>[];

  List<DevLogEntry> get entries => List.unmodifiable(_entries);

  void add(DevLogEntry entry) {
    _entries.insert(0, entry);
    if (_entries.length > AppConfig.devLogCapacity) {
      _entries.removeRange(AppConfig.devLogCapacity, _entries.length);
    }
    notifyListeners();
  }

  void clear() {
    _entries.clear();
    notifyListeners();
  }
}
