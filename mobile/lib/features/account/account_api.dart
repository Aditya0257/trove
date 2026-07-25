/// ============================================================================
///  account_api - self-service profile + security calls (/api/account)
/// ============================================================================
///
///  Purpose
///  -------
///  Everything the signed-in user manages about their own account: profile (name,
///  photo, email), password, and authenticator-app two-factor. Mirrors the web
///  client's AuthService account methods against the same endpoints.
/// ============================================================================
library;

import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/models/account.dart';
import '../../core/providers.dart';

class AccountApi {
  AccountApi(this._api);
  final ApiClient _api;

  Future<AccountProfile> me() async {
    final data = await _api.get('/api/account/me') as Map<String, dynamic>;
    return AccountProfile.fromJson(data);
  }

  Future<AccountProfile> updateDisplayName(String displayName) async {
    final data = await _api.post('/api/account/profile',
        body: {'displayName': displayName.trim()},) as Map<String, dynamic>;
    return AccountProfile.fromJson(data);
  }

  Future<void> changePassword(String currentPassword, String newPassword) async {
    await _api.post('/api/account/password',
        body: {'currentPassword': currentPassword, 'newPassword': newPassword},);
  }

  /// Begin an email change: re-checks the password and emails a code to the new address.
  Future<void> startEmailChange(String newEmail, String password) async {
    await _api.post('/api/account/email',
        body: {'newEmail': newEmail.trim(), 'password': password},);
  }

  Future<AccountProfile> verifyEmailChange(String code) async {
    final data = await _api.post('/api/account/email/verify',
        body: {'code': code.trim()},) as Map<String, dynamic>;
    return AccountProfile.fromJson(data);
  }

  /// Upload a profile photo; returns the presigned URL of the stored image.
  Future<String?> uploadPhoto(File file) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(file.path),
    });
    final data = await _api.postMultipart('/api/account/photo', form)
        as Map<String, dynamic>;
    return data['avatarUrl'] as String?;
  }

  Future<void> deletePhoto() async {
    await _api.delete('/api/account/photo');
  }

  // ---- two-factor (TOTP) --------------------------------------------------

  Future<bool> twoFactorStatus() async {
    final data = await _api.get('/api/account/2fa/status') as Map<String, dynamic>;
    return (data['enabled'] as bool?) ?? false;
  }

  /// Start enrollment: returns {secret, otpauthUri} to add to an authenticator app.
  Future<Map<String, String>> twoFactorSetup() async {
    final data = await _api.post('/api/account/2fa/setup') as Map<String, dynamic>;
    return {
      'secret': data['secret'] as String? ?? '',
      'otpauthUri': data['otpauthUri'] as String? ?? '',
    };
  }

  Future<void> twoFactorEnable(String code) async {
    await _api.post('/api/account/2fa/enable', body: {'code': code.trim()});
  }

  Future<void> twoFactorDisable(String code) async {
    await _api.post('/api/account/2fa/disable', body: {'code': code.trim()});
  }
}

final accountApiProvider = Provider<AccountApi>(
  (ref) => AccountApi(ref.watch(apiClientProvider)),
);

/// The signed-in user's profile, refreshable by the account screen.
final accountProfileProvider = FutureProvider.autoDispose<AccountProfile>(
  (ref) => ref.watch(accountApiProvider).me(),
);
