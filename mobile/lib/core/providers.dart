/// ============================================================================
///  providers — Riverpod wiring for the core singletons
/// ============================================================================
///
///  Purpose
///  -------
///  One place that constructs and exposes the shared services: AuthStore, ApiClient
///  (fed the token from AuthStore), and the singleton NoticeCenter / DeveloperLog.
///  Feature controllers depend on these.
///
///  Design
///  ------
///  AuthStore is a ChangeNotifier exposed via ChangeNotifierProvider so router +
///  widgets can watch it. ApiClient reads the token lazily so there's no init cycle.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'api/api_client.dart';
import 'auth/auth_store.dart';
import 'notice/dev_log.dart';
import 'notice/notice_center.dart';

/// The persisted session. Its `restore()` is awaited in main() before runApp.
final authStoreProvider = ChangeNotifierProvider<AuthStore>((ref) => AuthStore());

/// The notice-aware HTTP client, wired to read the current JWT from the AuthStore.
final apiClientProvider = Provider<ApiClient>((ref) {
  final auth = ref.watch(authStoreProvider);
  return ApiClient(tokenGetter: () async => auth.token);
});

/// Singletons for the Notice System surfaces (also reachable statically).
final noticeCenterProvider = Provider<NoticeCenter>((ref) => NoticeCenter.instance);
final developerLogProvider = Provider<DeveloperLog>((ref) => DeveloperLog.instance);
