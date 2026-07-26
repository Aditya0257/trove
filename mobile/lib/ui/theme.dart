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
      // A filled field with a *visible* rounded outline. The visible border is the
      // fix for the "label floats outside the box" bug: a floating label notches the
      // outline, so with an invisible (BorderSide.none) border it appeared to hang in
      // empty space above the field. A real 1px outline gives the notch something to
      // sit on, and the fill keeps the calm look. Applies to every field in the app.
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: scheme.surfaceContainerHighest.withValues(alpha: 0.35),
        isDense: true,
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 16),
        hintStyle: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant),
        labelStyle: TextStyle(fontSize: 15, color: scheme.onSurfaceVariant),
        floatingLabelStyle: TextStyle(fontSize: 13, color: scheme.primary),
        floatingLabelBehavior: FloatingLabelBehavior.auto,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: scheme.outlineVariant),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: scheme.outlineVariant),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: scheme.primary, width: 1.5),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          // A comfortable height and a sane minimum width. Deliberately NOT
          // Size.fromHeight (which is Size(infinity, h)): an infinite minimum width
          // makes a FilledButton crash inside a Row/Wrap (unbounded main axis) and
          // stops action buttons from sizing to their label. Full-width buttons get
          // their width from a stretch Column or a ListView (tight constraints), so
          // they are unaffected by this.
          minimumSize: const Size(64, 52),
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
