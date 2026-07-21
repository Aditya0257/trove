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

class AuthController extends AsyncNotifier<void> {
  @override
  Future<void> build() async {}

  ApiClient get _api => ref.read(apiClientProvider);

  Future<bool> login(String email, String password) =>
      _run('/api/auth/login', email, password, 'Welcome back, ');

  Future<bool> register(String email, String password, String? displayName) =>
      _run('/api/auth/register', email, password, 'Welcome to Trove, ',
          displayName: displayName,);

  Future<void> logout() async {
    await ref.read(authStoreProvider).clear();
    NoticeCenter.instance.show(Notice.local(
      level: NoticeLevel.info,
      code: 'SIGNED_OUT',
      userMessage: 'Signed out. Your documents stay safe in the vault.',
    ),);
  }

  Future<bool> _run(String path, String email, String password, String greeting,
      {String? displayName,}) async {
    state = const AsyncLoading();
    try {
      final body = {
        'email': email.trim(),
        'password': password,
        if (displayName != null && displayName.isNotEmpty) 'displayName': displayName,
      };
      final data = await _api.post(path, body: body, silent: true) as Map<String, dynamic>;
      final user = AuthUser.fromJson(data);
      await ref.read(authStoreProvider).save(data['token'] as String, user);
      state = const AsyncData(null);
      NoticeCenter.instance.show(Notice.local(
        level: NoticeLevel.success,
        code: 'SIGNED_IN',
        userMessage: '$greeting${user.shortName}.',
      ),);
      return true;
    } on ApiException catch (e) {
      state = AsyncError(e, StackTrace.current);
      NoticeCenter.instance.show(e.notice); // tailored, e.g. "email already registered"
      return false;
    } catch (e) {
      state = AsyncError(e, StackTrace.current);
      return false;
    }
  }
}
