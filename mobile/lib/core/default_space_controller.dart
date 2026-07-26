/// ============================================================================
///  DefaultSpaceController - the space a user lands in, remembered across launches
/// ============================================================================
///
///  Purpose
///  -------
///  A user always has a personal space, and may belong to several shared ones. Rather
///  than make them re-pick a space every time, we remember one as their default: the
///  home shell targets it (quick-add, the left drawer's space-scoped items), and the
///  user can switch it from the spaces list. Persisted in the OS keychain (same store
///  as the session and theme), so the choice sticks across reloads and sign-ins.
///
///  Design
///  ------
///  A StateNotifier<String?> holding the chosen space id (null = fall back to the
///  personal space). Exposed as a Riverpod provider the home shell watches.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class DefaultSpaceController extends StateNotifier<String?> {
  DefaultSpaceController(this._storage) : super(null) {
    _load();
  }

  static const _key = 'trove.defaultSpaceId';
  final FlutterSecureStorage _storage;

  Future<void> _load() async {
    state = await _storage.read(key: _key);
  }

  /// Set (or clear, with null) the default space and persist it.
  Future<void> set(String? spaceId) async {
    state = spaceId;
    if (spaceId == null) {
      await _storage.delete(key: _key);
    } else {
      await _storage.write(key: _key, value: spaceId);
    }
  }
}

final defaultSpaceProvider = StateNotifierProvider<DefaultSpaceController, String?>(
  (ref) => DefaultSpaceController(const FlutterSecureStorage()),
);
