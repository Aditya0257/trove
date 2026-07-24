/// ============================================================================
///  AuthController — login / register / logout
/// ============================================================================
///
///  Purpose
///  -------
///  The auth use-cases: call the API, persist the session in AuthStore, and surface a
///  friendly notice on success/failure (failures also auto-toast via the ApiClient
///  interceptor; here we add a welcoming success notice).
///
///  Design
///  ------
///  A Riverpod AsyncNotifier exposing `busy` state for the login button. Auth calls
///  are made `silent` so we can show a tailored message instead of the raw error toast.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/api_client.dart';
import '../api/api_exception.dart';
import '../models/user.dart';
import '../notice/notice.dart';
import '../notice/notice_center.dart';
import '../providers.dart';

final authControllerProvider =
    AsyncNotifierProvider<AuthController, void>(AuthController.new);

/// What a sign-in / sign-up attempt led to. The screen reacts: reveal the code
/// field for [needCode], keep the form up for [pending]/[failed], route on [success].
enum AuthOutcome { success, needCode, pending, failed }

class AuthController extends AsyncNotifier<void> {
  @override
  Future<void> build() async {}

  ApiClient get _api => ref.read(apiClientProvider);

  /// Sign in. When the account has 2FA on, the first call (no [code]) comes back as
  /// [AuthOutcome.needCode]; the screen then resubmits with the 6-digit code.
  Future<AuthOutcome> login(String email, String password, {String? code}) =>
      _run('/api/auth/login', email, password, 'Welcome back, ', code: code);

  Future<AuthOutcome> register(String email, String password, String? displayName) =>
      _run('/api/auth/register', email, password, 'Welcome to Trove, ',
          displayName: displayName,);

  /// Begin a password reset. Always shows the same message (anti-enumeration); the
  /// reset itself is completed by opening the emailed link on the web.
  Future<void> forgotPassword(String email) async {
    try {
      await _api.post('/api/auth/forgot-password', body: {'email': email.trim()}, silent: true);
    } catch (_) {
      // ignore: the response is intentionally the same whether or not the email exists
    }
    NoticeCenter.instance.show(Notice.local(
      level: NoticeLevel.info,
      code: 'RESET_SENT',
      userMessage: 'If that email is registered, a reset link is on its way. Open it on the web to set a new password.',
    ),);
  }

  Future<void> logout() async {
    await ref.read(authStoreProvider).clear();
    NoticeCenter.instance.show(Notice.local(
      level: NoticeLevel.info,
      code: 'SIGNED_OUT',
      userMessage: 'Signed out. Your documents stay safe in the vault.',
    ),);
  }

  Future<AuthOutcome> _run(String path, String email, String password, String greeting,
      {String? displayName, String? code,}) async {
    state = const AsyncLoading();
    try {
      final body = {
        'email': email.trim(),
        'password': password,
        if (displayName != null && displayName.isNotEmpty) 'displayName': displayName,
        if (code != null && code.isNotEmpty) 'code': code.trim(),
      };
      final data = await _api.post(path, body: body, silent: true) as Map<String, dynamic>;
      final token = data['token'] as String?;

      // Full session: a real token was issued.
      if (token != null) {
        final user = AuthUser.fromJson(data);
        await ref.read(authStoreProvider).save(token, user);
        state = const AsyncData(null);
        NoticeCenter.instance.show(Notice.local(
          level: NoticeLevel.success,
          code: 'SIGNED_IN',
          userMessage: '$greeting${user.shortName}.',
        ),);
        return AuthOutcome.success;
      }

      // No token. Either 2FA is required, or the account isn't active yet.
      state = const AsyncData(null);
      if (data['twoFactorRequired'] == true) {
        return AuthOutcome.needCode;
      }
      final status = data['status'] as String?;
      NoticeCenter.instance.show(Notice.local(
        level: NoticeLevel.info,
        code: 'ACCOUNT_$status',
        userMessage: switch (status) {
          'pending' => 'Your account is awaiting admin approval. You can sign in once it is approved.',
          'rejected' => 'Access to Trove was declined for this account.',
          _ => 'Could not complete sign-in. Please try again.',
        },
      ),);
      return AuthOutcome.pending;
    } on ApiException catch (e) {
      state = AsyncError(e, StackTrace.current);
      NoticeCenter.instance.show(e.notice); // tailored, e.g. "invalid authenticator code"
      return AuthOutcome.failed;
    } catch (e) {
      state = AsyncError(e, StackTrace.current);
      return AuthOutcome.failed;
    }
  }
}
