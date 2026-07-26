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

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/api_client.dart';
import '../api/api_exception.dart';
import '../models/user.dart';
import '../notice/notice.dart';
import '../notice/notice_center.dart';
import '../providers.dart';

final authControllerProvider =
    AsyncNotifierProvider<AuthController, void>(AuthController.new);

/// What a sign-in / sign-up attempt led to. The screen reacts: reveal the 2FA code
/// field for [needCode], the email-code field for [needVerify], keep the form up for
/// [pending]/[failed], route on [success].
enum AuthOutcome { success, needCode, needVerify, pending, failed }

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

  /// Confirm the sign-up email with the 6-digit code. On success a token (open
  /// registration/admin) signs the user in; otherwise the account is verified and now
  /// awaiting admin approval.
  Future<AuthOutcome> verifyEmail(String email, String code) async {
    state = const AsyncLoading();
    try {
      final data = await _api.post('/api/auth/verify-email',
          body: {'email': email.trim(), 'code': code.trim()}, silent: true,) as Map<String, dynamic>;
      final token = data['token'] as String?;
      state = const AsyncData(null);
      if (token != null) {
        final user = AuthUser.fromJson(data);
        await ref.read(authStoreProvider).save(token, user);
        NoticeCenter.instance.show(Notice.local(
          level: NoticeLevel.success, code: 'SIGNED_IN',
          userMessage: 'Email verified. Welcome, ${user.shortName}.',
        ),);
        return AuthOutcome.success;
      }
      NoticeCenter.instance.show(Notice.local(
        level: NoticeLevel.info, code: 'VERIFIED_PENDING',
        userMessage: 'Email verified. Your account is now awaiting admin approval.',
      ),);
      return AuthOutcome.pending;
    } catch (e) {
      state = const AsyncData(null);
      NoticeCenter.instance.show(Notice.local(
        level: NoticeLevel.warning, code: 'VERIFY_FAILED',
        userMessage: 'That code is incorrect or expired. Check it, or resend a new one.',
      ),);
      return AuthOutcome.failed;
    }
  }

  /// Resend the email verification code. Always reports the same (anti-enumeration).
  Future<void> resendVerification(String email) async {
    try {
      await _api.post('/api/auth/resend-verification', body: {'email': email.trim()}, silent: true);
    } catch (_) {
      // ignore: response is intentionally the same regardless of whether the email exists
    }
    NoticeCenter.instance.show(Notice.local(
      level: NoticeLevel.info, code: 'CODE_RESENT',
      userMessage: 'Code resent. Check your inbox and spam.',
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

      // No token. Either 2FA is required, the email isn't verified, or not active yet.
      state = const AsyncData(null);
      if (data['twoFactorRequired'] == true) {
        return AuthOutcome.needCode;
      }
      final status = data['status'] as String?;
      if (status == 'unverified') {
        return AuthOutcome.needVerify; // screen shows the email-code step
      }
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
    } catch (e) {
      // The auth calls are `silent`, so the interceptor does NOT toast; we must surface
      // the reason here. The client rejects with a DioException whose `error` is our
      // ApiException, so an `on ApiException` clause would never match - hence a failed
      // sign-in used to do nothing at all. Show a clear message instead.
      state = AsyncError(e, StackTrace.current);
      NoticeCenter.instance.show(_authErrorNotice(e));
      return AuthOutcome.failed;
    }
  }

  /// A friendly notice for a failed sign-in / sign-up: bad credentials read as exactly
  /// that (not the generic "please sign in again"), other server errors keep their own
  /// message, and a network failure says so.
  Notice _authErrorNotice(Object e) {
    final api = e is DioException && e.error is ApiException
        ? e.error! as ApiException
        : (e is ApiException ? e : null);
    if (api != null) {
      if (api.statusCode == 401 || api.statusCode == 403) {
        return Notice.local(
          level: NoticeLevel.warning,
          code: 'SIGN_IN_FAILED',
          userMessage: 'Incorrect email or password. Please try again.',
        );
      }
      return api.notice;
    }
    return Notice.local(
      level: NoticeLevel.error,
      code: 'SIGN_IN_FAILED',
      userMessage: "Couldn't sign in. Check your connection and try again.",
    );
  }
}
