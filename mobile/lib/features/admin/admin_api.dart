/// ============================================================================
///  admin_api - admin-only account approvals + user management (/api/admin)
/// ============================================================================
///
///  Purpose
///  -------
///  The calls an admin makes to run the vault's small user base: approve or reject
///  accounts awaiting sign-off, list every account, and permanently delete an
///  account together with all of its data. Mirrors the web client's AdminService
///  against the same endpoints.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/models/account.dart';
import '../../core/providers.dart';

/// A person who has registered but is still awaiting an admin's approval.
class PendingUser {
  const PendingUser({
    required this.id,
    required this.email,
    required this.displayName,
    this.requestedAt,
  });

  final String id;
  final String email;
  final String displayName;
  final String? requestedAt;

  factory PendingUser.fromJson(Map<String, dynamic> json) => PendingUser(
        id: json['id'] as String,
        email: json['email'] as String? ?? '',
        displayName: json['displayName'] as String? ?? '',
        requestedAt: json['requestedAt'] as String?,
      );
}

class AdminApi {
  AdminApi(this._api);
  final ApiClient _api;

  Future<List<PendingUser>> pending() async {
    final data = await _api.get('/api/admin/pending') as List<dynamic>;
    return data
        .map((e) => PendingUser.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  Future<void> approve(String id) async {
    await _api.post('/api/admin/users/$id/approve');
  }

  Future<void> reject(String id) async {
    await _api.post('/api/admin/users/$id/reject');
  }

  Future<List<AdminUser>> users() async {
    final data = await _api.get('/api/admin/users') as List<dynamic>;
    return data
        .map((e) => AdminUser.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  Future<void> deleteUser(String id, String confirmEmail) async {
    await _api.post('/api/admin/users/$id/delete',
        body: {'confirmEmail': confirmEmail},);
  }
}

final adminApiProvider = Provider<AdminApi>(
  (ref) => AdminApi(ref.watch(apiClientProvider)),
);

/// Accounts awaiting an admin's approval, refreshable by the admin screen.
final adminPendingProvider = FutureProvider.autoDispose<List<PendingUser>>(
  (ref) => ref.watch(adminApiProvider).pending(),
);

/// Every account in the vault, refreshable by the admin screen.
final adminUsersProvider = FutureProvider.autoDispose<List<AdminUser>>(
  (ref) => ref.watch(adminApiProvider).users(),
);
