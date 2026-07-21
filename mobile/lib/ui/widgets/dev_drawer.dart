/// ============================================================================
///  DeveloperDrawer — the in-app "inspect" surface (D23)
/// ============================================================================
///
///  Purpose
///  -------
///  A slide-over that lists every API call the app made (newest first): method,
///  path, status, client round-trip ms, the server request-id, the notice, and — for
///  document reads — the extraction attempt trail. The mobile equivalent of the web's
///  styled console: lots of clean info, monospace, no emoji.
///
///  Design
///  ------
///  Listens to the DeveloperLog singleton. Rows expand to show the notice devNote and
///  the extraction trail (provider · status · confidence% · latency). Nothing secret.
/// ============================================================================
library;

import 'package:flutter/material.dart';

import '../../core/notice/dev_log.dart';

class DeveloperDrawer extends StatelessWidget {
  const DeveloperDrawer({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Drawer(
      width: MediaQuery.of(context).size.width * 0.92,
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 8, 8),
              child: Row(
                children: [
                  const Text('Developer',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),),
                  const SizedBox(width: 8),
                  Text('request trail',
                      style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),),
                  const Spacer(),
                  TextButton(
                    onPressed: () => DeveloperLog.instance.clear(),
                    child: const Text('Clear'),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: ListenableBuilder(
                listenable: DeveloperLog.instance,
                builder: (context, _) {
                  final entries = DeveloperLog.instance.entries;
                  if (entries.isEmpty) {
                    return Center(
                      child: Text('No requests yet.',
                          style: TextStyle(color: scheme.onSurfaceVariant),),
                    );
                  }
                  return ListView.separated(
                    itemCount: entries.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (context, i) => _EntryTile(entry: entries[i]),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _EntryTile extends StatelessWidget {
  const _EntryTile({required this.entry});
  final DevLogEntry entry;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final statusColor = !entry.reachedServer
        ? scheme.error
        : entry.ok
            ? const Color(0xFF2E7D5B)
            : const Color(0xFFB8860B);
    final trail = entry.extractionMeta?['attempts'];

    return ExpansionTile(
      tilePadding: const EdgeInsets.symmetric(horizontal: 16),
      childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      title: Row(
        children: [
          _chip(entry.method, scheme.primary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(entry.path,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 13),),
          ),
          _chip(entry.reachedServer ? '${entry.statusCode}' : 'ERR', statusColor),
        ],
      ),
      subtitle: Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Text(
          '${entry.durationMs}ms'
          '${entry.requestId != null ? '  ·  req ${entry.requestId}' : ''}',
          style: TextStyle(
              fontFamily: 'monospace', fontSize: 11, color: scheme.onSurfaceVariant,),
        ),
      ),
      children: [
        if (entry.notice != null) ...[
          _kv('notice', '${entry.notice!.code} (${entry.notice!.level.name})', scheme),
          _kv('user', entry.notice!.userMessage, scheme),
          if ((entry.notice!.devNote ?? '').isNotEmpty)
            _kv('dev', entry.notice!.devNote!, scheme),
        ],
        if (trail is List && trail.isNotEmpty) ...[
          const SizedBox(height: 6),
          Align(
            alignment: Alignment.centerLeft,
            child: Text('extraction chain',
                style: TextStyle(
                    fontSize: 11, fontWeight: FontWeight.w700, color: scheme.onSurfaceVariant,),),
          ),
          for (final a in trail.cast<Map<dynamic, dynamic>>())
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                '${a['provider']} · ${a['status']}'
                '${a['confidencePct'] != null ? ' · ${a['confidencePct']}%' : ''}'
                ' · ${a['latencyMs']}ms',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
              ),
            ),
        ],
      ],
    );
  }

  Widget _chip(String text, Color color) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.14),
          borderRadius: BorderRadius.circular(6),
        ),
        child: Text(text,
            style: TextStyle(
                fontFamily: 'monospace',
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: color,),),
      );

  Widget _kv(String k, String v, ColorScheme scheme) => Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 52,
              child: Text(k,
                  style: TextStyle(
                      fontSize: 11, fontWeight: FontWeight.w700, color: scheme.onSurfaceVariant,),),
            ),
            Expanded(
              child: Text(v, style: const TextStyle(fontSize: 12, height: 1.3)),
            ),
          ],
        ),
      );
}
