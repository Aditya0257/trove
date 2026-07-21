/// ============================================================================
///  AppTheme — a clean, senior Material 3 palette (light + dark)
/// ============================================================================
///  Purpose:  one calm, considered visual identity for Trove — restrained colour,
///            generous spacing, monospace for the Developer surface. No emoji chrome.
/// ============================================================================
library;

import 'package:flutter/material.dart';

import '../core/notice/notice.dart';

class AppTheme {
  const AppTheme._();

  static const seed = Color(0xFF2F6F6A); // muted teal — vault-calm, not flashy

  static ThemeData light() => _base(Brightness.light);
  static ThemeData dark() => _base(Brightness.dark);

  static ThemeData _base(Brightness b) {
    final scheme = ColorScheme.fromSeed(seedColor: seed, brightness: b);
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: scheme.surface,
      appBarTheme: AppBarTheme(
        backgroundColor: scheme.surface,
        foregroundColor: scheme.onSurface,
        elevation: 0,
        centerTitle: false,
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        color: scheme.surfaceContainerHighest.withValues(alpha: 0.4),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: scheme.surfaceContainerHighest.withValues(alpha: 0.4),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide.none,
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size.fromHeight(52),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
      ),
    );
  }

  /// Accent colour for a notice level (used by the toast + drawer).
  static Color noticeColor(ColorScheme scheme, NoticeLevel level) {
    switch (level) {
      case NoticeLevel.success:
        return const Color(0xFF2E7D5B);
      case NoticeLevel.warning:
        return const Color(0xFFB8860B);
      case NoticeLevel.error:
        return scheme.error;
      case NoticeLevel.info:
        return scheme.primary;
    }
  }
}
