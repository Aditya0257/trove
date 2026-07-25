/// ============================================================================
///  ThemeController - light / dark / system, remembered across launches
/// ============================================================================
///
///  Purpose
///  -------
///  Holds the app's ThemeMode and persists the choice in the OS keychain (same store
///  as the session), so a user's light/dark preference sticks. Exposed as a Riverpod
///  StateNotifierProvider that MaterialApp watches for `themeMode`.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class ThemeController extends StateNotifier<ThemeMode> {
  ThemeController(this._storage) : super(ThemeMode.system) {
    _load();
  }

  static const _key = 'trove.themeMode';
  final FlutterSecureStorage _storage;

  Future<void> _load() async {
    final v = await _storage.read(key: _key);
    state = switch (v) {
      'light' => ThemeMode.light,
      'dark' => ThemeMode.dark,
      _ => ThemeMode.system,
    };
  }

  Future<void> set(ThemeMode mode) async {
    state = mode;
    await _storage.write(key: _key, value: mode.name);
  }
}

final themeModeProvider = StateNotifierProvider<ThemeController, ThemeMode>(
  (ref) => ThemeController(const FlutterSecureStorage()),
);
