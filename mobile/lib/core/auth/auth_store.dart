/// ============================================================================
///  AuthStore — durable session (JWT + current user) in the OS keychain
/// ============================================================================
///
///  Purpose
///  -------
///  Holds the signed-in session: the JWT and the AuthUser. Persists them in the OS
///  secure storage (keychain/keystore) so the app stays signed in across launches,
///  and exposes the token to the ApiClient via a getter.
///
///  Business use case
///  -----------------
///  Trove is a personal vault — you sign in once on your phone and stay in, securely.
///
///  Design
///  ------
///  ChangeNotifier so `go_router` can redirect on auth changes and widgets can react.
///  Token is NEVER logged. `restore()` runs at startup before the first frame decides
///  the initial route.
/// ============================================================================
library;

import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../models/user.dart';

class AuthStore extends ChangeNotifier {
  AuthStore({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage();

  static const _kToken = 'trove.token';
  static const _kUser = 'trove.user';

  final FlutterSecureStorage _storage;

  String? _token;
  AuthUser? _user;
  bool _restored = false;

  String? get token => _token;
  AuthUser? get user => _user;
  bool get isAuthenticated => _token != null && _token!.isNotEmpty;
  bool get restored => _restored;

  /// Load any persisted session. Call once at startup.
  Future<void> restore() async {
    _token = await _storage.read(key: _kToken);
    final rawUser = await _storage.read(key: _kUser);
    if (rawUser != null) {
      _user = AuthUser.fromJson(jsonDecode(rawUser) as Map<String, dynamic>);
    }
    _restored = true;
    notifyListeners();
  }

  Future<void> save(String token, AuthUser user) async {
    _token = token;
    _user = user;
    await _storage.write(key: _kToken, value: token);
    await _storage.write(key: _kUser, value: jsonEncode(user.toJson()));
    notifyListeners();
  }

  Future<void> clear() async {
    _token = null;
    _user = null;
    await _storage.delete(key: _kToken);
    await _storage.delete(key: _kUser);
    notifyListeners();
  }
}
