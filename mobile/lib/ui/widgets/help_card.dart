/// ============================================================================
///  HelpCard + InfoTip - in-app explainers (the mobile twin of the web help)
/// ============================================================================
///
///  Purpose
///  -------
///  Two lightweight, reusable explainers so every screen can teach itself:
///  - HelpCard: a collapsible "Help" panel with a plain "what this is" line and an
///    optional "how it works" technical note, collapsed by default.
///  - InfoTip: a small round (i) next to a field/label that opens a short note.
///
///  Design
///  ------
///  Material 3, theme-aware. HelpCard uses an ExpansionTile so a repeat user is not
///  forced to re-read it. InfoTip opens a compact dialog on tap (reliable on touch,
///  unlike a hover tooltip).
/// ============================================================================
library;

import 'package:flutter/material.dart';

class HelpCard extends StatelessWidget {
  const HelpCard({
    required this.title,
    required this.user,
    this.dev,
    this.initiallyExpanded = false,
    super.key,
  });

  final String title;
  final String user;
  final String? dev;
  final bool initiallyExpanded;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      color: scheme.primaryContainer.withValues(alpha: 0.25),
      child: ExpansionTile(
          // Borderless shapes drop ExpansionTile's default dividers without wrapping the
          // subtree in a Theme (an InheritedWidget), which could be torn down mid-navigation.
          shape: const RoundedRectangleBorder(side: BorderSide.none),
          collapsedShape: const RoundedRectangleBorder(side: BorderSide.none),
          initiallyExpanded: initiallyExpanded,
          tilePadding: const EdgeInsets.symmetric(horizontal: 14),
          childrenPadding: const EdgeInsets.fromLTRB(14, 0, 14, 14),
          leading: Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
            decoration: BoxDecoration(
              color: scheme.primary,
              borderRadius: BorderRadius.circular(6),
            ),
            child: Text(
              'HELP',
              style: TextStyle(
                color: scheme.onPrimary,
                fontSize: 10,
                fontWeight: FontWeight.w800,
                letterSpacing: 0.4,
              ),
            ),
          ),
          title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
          children: [
            Align(
              alignment: Alignment.centerLeft,
              child: Text(user, style: TextStyle(color: scheme.onSurface, fontSize: 13, height: 1.5)),
            ),
            if (dev != null && dev!.isNotEmpty) ...[
              const SizedBox(height: 10),
              Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  'HOW IT WORKS',
                  style: TextStyle(
                    color: scheme.onSurfaceVariant,
                    fontSize: 10,
                    fontWeight: FontWeight.w800,
                    letterSpacing: 0.4,
                  ),
                ),
              ),
              const SizedBox(height: 3),
              Align(
                alignment: Alignment.centerLeft,
                child: Text(dev!, style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12, height: 1.5)),
              ),
            ],
          ],
        ),
    );
  }
}

/// A small round (i) that opens a short explanation on tap. Put it next to a label.
class InfoTip extends StatelessWidget {
  const InfoTip({required this.text, this.title, super.key});

  final String text;
  final String? title;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      customBorder: const CircleBorder(),
      onTap: () => showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text(title ?? 'About this'),
          content: Text(text, style: const TextStyle(height: 1.5)),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Got it')),
          ],
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(2),
        child: Icon(Icons.help_outline, size: 18, color: scheme.onSurfaceVariant),
      ),
    );
  }
}
