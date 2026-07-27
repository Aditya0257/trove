/// ============================================================================
///  UsageOverview — free-tier usage across every backing service
/// ============================================================================
///
///  Purpose
///  -------
///  A read-only snapshot the Developer drawer renders as a stack of meters: the two
///  daily-reset pools (AI credits, email sends) and the running-total storage figures
///  (object store, database, mirror). Lets the closed circle see free-tier headroom
///  and the exact daily reset before going live.
///
///  Design
///  ------
///  Immutable value types with tolerant `fromJson`s that default every field. Fetched
///  via an autoDispose FutureProvider so it refreshes whenever the drawer reopens.
///  `dailyResetAt` is the next 00:00 UTC instant; the UI renders it in IST.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers.dart';

num _n(Object? v) => v is num ? v : 0;

class AiMeter {
  const AiMeter({
    required this.limitNeurons,
    required this.perUserLimitNeurons,
    required this.globalNeurons,
    required this.globalTokens,
    required this.userNeurons,
    required this.userTokens,
  });

  final num limitNeurons;
  final num perUserLimitNeurons;
  final num globalNeurons;
  final num globalTokens;
  final num userNeurons;
  final num userTokens;

  factory AiMeter.fromJson(Map<String, dynamic> j) => AiMeter(
        limitNeurons: _n(j['limitNeurons']),
        perUserLimitNeurons: _n(j['perUserLimitNeurons']),
        globalNeurons: _n(j['globalNeurons']),
        globalTokens: _n(j['globalTokens']),
        userNeurons: _n(j['userNeurons']),
        userTokens: _n(j['userTokens']),
      );
}

class EmailMeter {
  const EmailMeter({required this.dailyLimit, required this.sentToday});

  final num dailyLimit;
  final num sentToday;

  bool get reached => sentToday >= dailyLimit && dailyLimit > 0;

  factory EmailMeter.fromJson(Map<String, dynamic> j) =>
      EmailMeter(dailyLimit: _n(j['dailyLimit']), sentToday: _n(j['sentToday']));
}

class StoreMeter {
  const StoreMeter({required this.usedBytes, required this.limitBytes});

  final num usedBytes;
  final num limitBytes;

  factory StoreMeter.fromJson(Map<String, dynamic> j) =>
      StoreMeter(usedBytes: _n(j['usedBytes']), limitBytes: _n(j['limitBytes']));
}

class MirrorMeter {
  const MirrorMeter({required this.enabled, required this.usedBytes, required this.limitBytes});

  final bool enabled;
  final num usedBytes;
  final num limitBytes;

  factory MirrorMeter.fromJson(Map<String, dynamic> j) => MirrorMeter(
        enabled: j['enabled'] == true,
        usedBytes: _n(j['usedBytes']),
        limitBytes: _n(j['limitBytes']),
      );
}

class UsageOverview {
  const UsageOverview({
    required this.dailyResetAt,
    required this.ai,
    required this.email,
    required this.storage,
    required this.database,
    required this.mirror,
  });

  /// Next 00:00 UTC reset instant for the daily pools (null if unparseable).
  final DateTime? dailyResetAt;
  final AiMeter ai;
  final EmailMeter email;
  final StoreMeter storage;
  final StoreMeter database;
  final MirrorMeter mirror;

  factory UsageOverview.fromJson(Map<String, dynamic> j) {
    Map<String, dynamic> m(Object? v) => (v as Map?)?.cast<String, dynamic>() ?? const {};
    return UsageOverview(
      dailyResetAt: DateTime.tryParse(j['dailyResetAt']?.toString() ?? ''),
      ai: AiMeter.fromJson(m(j['ai'])),
      email: EmailMeter.fromJson(m(j['email'])),
      storage: StoreMeter.fromJson(m(j['storage'])),
      database: StoreMeter.fromJson(m(j['database'])),
      mirror: MirrorMeter.fromJson(m(j['mirror'])),
    );
  }
}

/// The free-tier usage snapshot, refreshed each time the drawer's gauge mounts.
final usageProvider = FutureProvider.autoDispose<UsageOverview>((ref) async {
  final api = ref.watch(apiClientProvider);
  // silent: the gauge poll shouldn't clutter the Developer request trail.
  final d = await api.get('/api/usage', silent: true) as Map<String, dynamic>;
  return UsageOverview.fromJson(d);
});
