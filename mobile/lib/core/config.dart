/// ============================================================================
///  AppConfig — where the client points and how it identifies itself
/// ============================================================================
///
///  Purpose
///  -------
///  Central, compile-time-overridable configuration: the API base URL and a couple
///  of tunables. Mirrors the web client's runtime `config.json` idea, but for a
///  compiled app it's a --dart-define (with a sensible default per platform).
///
///  Business use case
///  -----------------
///  One binary can target local dev, the Oracle host, or a staging URL without code
///  edits — pass `--dart-define=TROVE_API_BASE=https://api.example.com`.
///
///  Design
///  ------
///  The default is the Android-emulator alias for the host machine (10.0.2.2), which
///  is the most common dev case; override for iOS simulator (localhost) or prod.
/// ============================================================================
library;

class AppConfig {
  const AppConfig._();

  /// API origin. Override at build/run time:
  ///   --dart-define=TROVE_API_BASE=https://api.trove-sync.duckdns.org
  static const String apiBase = String.fromEnvironment(
    'TROVE_API_BASE',
    defaultValue: 'http://10.0.2.2:8080',
  );

  /// How many recent requests the in-app Developer drawer retains (D23).
  static const int devLogCapacity = 100;

  /// App display name.
  static const String appName = 'Trove';
}
