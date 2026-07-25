/// ============================================================================
///  AiUsage — today's AI credit + token spend (global and per-user)
/// ============================================================================
///
///  Purpose
///  -------
///  A small read-only snapshot of how much of the shared AI budget has been used
///  today: the global allowance and the per-user allowance, each with credits
///  (neurons) spent and tokens processed. Feeds the Developer drawer's usage gauge
///  so the closed circle can see the free-tier budget draining in real time.
///
///  Design
///  ------
///  Immutable value type with a tolerant `fromJson` that reads the nested
///  `global`/`user` objects and defaults every field to 0 when missing. Fetched via
///  an autoDispose FutureProvider so it refreshes whenever the drawer reopens.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers.dart';

class AiUsage {
  const AiUsage({
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

  factory AiUsage.fromJson(Map<String, dynamic> json) {
    final global = (json['global'] as Map?)?.cast<String, dynamic>() ?? const {};
    final user = (json['user'] as Map?)?.cast<String, dynamic>() ?? const {};
    num n(Object? v) => v is num ? v : 0;
    return AiUsage(
      limitNeurons: n(json['limitNeurons']),
      perUserLimitNeurons: n(json['perUserLimitNeurons']),
      globalNeurons: n(global['neurons']),
      globalTokens: n(global['tokens']),
      userNeurons: n(user['neurons']),
      userTokens: n(user['tokens']),
    );
  }
}

/// Today's AI-budget snapshot, refreshed each time the drawer's gauge mounts.
final aiUsageProvider = FutureProvider.autoDispose<AiUsage>((ref) async {
  final api = ref.watch(apiClientProvider);
  final d = await api.get('/api/ai-usage') as Map<String, dynamic>;
  return AiUsage.fromJson(d);
});
