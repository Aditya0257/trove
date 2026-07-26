/// ============================================================================
///  NoticeToast — the two-channel toast (user line + expandable dev note, D23)
/// ============================================================================
///
///  Purpose
///  -------
///  Renders a single Notice: a calm coloured banner with the userMessage, an
///  accent bar by level, and — when a devNote exists — a "Developer note" the user
///  can expand. This is the mobile twin of the web toast.
///
///  Design
///  ------
///  Self-dismisses after a level-dependent delay unless the user is expanding it.
///  Presented as an overlay entry by the root (see main.dart / NoticeHost). The card
///  uses a UNIFORM outline (a non-uniform border with a borderRadius is an invalid
///  BoxDecoration and paints as a blank box); the level accent is a separate clipped
///  strip down the left edge.
/// ============================================================================
library;

import 'package:flutter/material.dart';

import '../../core/notice/notice.dart';
import '../theme.dart';

class NoticeToast extends StatefulWidget {
  const NoticeToast({super.key, required this.notice, required this.onDismiss});

  final Notice notice;
  final VoidCallback onDismiss;

  @override
  State<NoticeToast> createState() => _NoticeToastState();
}

class _NoticeToastState extends State<NoticeToast> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final accent = AppTheme.noticeColor(scheme, widget.notice.level);
    final hasDev = (widget.notice.devNote ?? '').isNotEmpty;

    // surfaceContainerHigh sits a step above the scaffold's `surface`, so the toast
    // reads as a distinct raised card in dark mode instead of blending into the
    // background. The outline is uniform (required when a borderRadius is set); the
    // level accent is a 4px strip on the left, clipped to the rounded corners.
    return Material(
      color: Colors.transparent,
      child: Container(
        decoration: BoxDecoration(
          color: scheme.surfaceContainerHigh,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: scheme.outlineVariant),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.28),
              blurRadius: 18,
              offset: const Offset(0, 6),
            ),
          ],
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(14),
          child: IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Container(width: 4, color: accent),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Expanded(
                              child: Text(
                                widget.notice.userMessage,
                                style: Theme.of(context).textTheme.bodyMedium,
                              ),
                            ),
                            IconButton(
                              icon: const Icon(Icons.close, size: 18),
                              onPressed: widget.onDismiss,
                              visualDensity: VisualDensity.compact,
                            ),
                          ],
                        ),
                        if (hasDev)
                          InkWell(
                            onTap: () => setState(() => _expanded = !_expanded),
                            child: Padding(
                              padding: const EdgeInsets.only(top: 4),
                              child: Row(
                                children: [
                                  Icon(_expanded ? Icons.expand_less : Icons.expand_more,
                                      size: 16, color: accent,),
                                  const SizedBox(width: 4),
                                  Text('Developer note',
                                      style: TextStyle(
                                          fontSize: 12,
                                          color: accent,
                                          fontWeight: FontWeight.w600,),),
                                  const SizedBox(width: 8),
                                  Text(widget.notice.code,
                                      style: TextStyle(
                                          fontSize: 11,
                                          fontFamily: 'monospace',
                                          color: scheme.onSurfaceVariant,),),
                                ],
                              ),
                            ),
                          ),
                        if (hasDev && _expanded)
                          Padding(
                            padding: const EdgeInsets.only(top: 6),
                            child: Text(
                              widget.notice.devNote!,
                              style: TextStyle(
                                fontSize: 12,
                                fontFamily: 'monospace',
                                color: scheme.onSurfaceVariant,
                                height: 1.35,
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
