/// ============================================================================
///  ChartPrefsController - the chart views chosen on the Spend screen, remembered
/// ============================================================================
///
///  Purpose
///  -------
///  The Spend screen draws two charts, each with a view toggle: the category
///  breakdown as bars or a donut, and the over-time series as bars or a wave.
///  Rather than reset to bars every visit, we remember each choice so the user
///  sees their preferred view next time. Persisted in the OS keychain (same store
///  as the session, theme and default space), so it sticks across reloads and
///  sign-ins on the device.
///
///  Design
///  ------
///  A small immutable ChartPrefs (two bools) held in a StateNotifier, exposed as a
///  Riverpod provider the Spend screen watches. Setters persist each field.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Which view each Spend chart is drawn as. `false` = bars (the default).
class ChartPrefs {
  const ChartPrefs({this.timeWave = false, this.catDonut = false});

  /// Over-time series: false = bars, true = wave.
  final bool timeWave;

  /// Category breakdown: false = bars, true = donut.
  final bool catDonut;

  ChartPrefs copyWith({bool? timeWave, bool? catDonut}) => ChartPrefs(
        timeWave: timeWave ?? this.timeWave,
        catDonut: catDonut ?? this.catDonut,
      );
}

class ChartPrefsController extends StateNotifier<ChartPrefs> {
  ChartPrefsController(this._storage) : super(const ChartPrefs()) {
    _load();
  }

  static const _timeKey = 'trove.chart.timeWave';
  static const _catKey = 'trove.chart.catDonut';
  final FlutterSecureStorage _storage;

  Future<void> _load() async {
    final time = await _storage.read(key: _timeKey);
    final cat = await _storage.read(key: _catKey);
    state = ChartPrefs(timeWave: time == 'true', catDonut: cat == 'true');
  }

  Future<void> setTimeWave(bool value) async {
    state = state.copyWith(timeWave: value);
    await _storage.write(key: _timeKey, value: value.toString());
  }

  Future<void> setCatDonut(bool value) async {
    state = state.copyWith(catDonut: value);
    await _storage.write(key: _catKey, value: value.toString());
  }
}

final chartPrefsProvider =
    StateNotifierProvider<ChartPrefsController, ChartPrefs>(
  (ref) => ChartPrefsController(const FlutterSecureStorage()),
);
