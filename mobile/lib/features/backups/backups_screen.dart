/// ============================================================================
///  BackupsScreen - "Backups & data health" (read-only + Drive sync trigger)
/// ============================================================================
///
///  Purpose
///  -------
///  Make Trove's core promise visible: the data is not disposable. Shows how many
///  independent copies of a space's documents exist (primary R2, sidecars, mirror,
///  Drive), a history of backup runs, and the Google Drive connection - with a single
///  "Sync now" button to kick the human-navigable Tier 3 mirror. Everything else here
///  is read-only. Loads the three views into local state and pulls-to-refresh together.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/notice/notice.dart';
import '../../core/notice/notice_center.dart';
import '../../ui/widgets/help_card.dart';
import 'backups_api.dart';

// TODO(backups): on-demand export/import ZIP (blob download/upload) is web-only for now.

class BackupsScreen extends ConsumerStatefulWidget {
  const BackupsScreen({required this.spaceId, super.key});
  final String spaceId;

  @override
  ConsumerState<BackupsScreen> createState() => _BackupsScreenState();
}

class _BackupsScreenState extends ConsumerState<BackupsScreen> {
  IntegrityReport? _integrity;
  List<BackupRun>? _history;
  DriveStatus? _drive;

  bool _loading = true;
  bool _failed = false;
  bool _busySync = false;

  BackupsApi get _api => ref.read(backupsApiProvider);

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _toast(NoticeLevel level, String code, String message) {
    NoticeCenter.instance.show(Notice.local(level: level, code: code, userMessage: message));
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _failed = false;
    });
    try {
      final results = await Future.wait([
        _api.integrity(widget.spaceId),
        _api.history(),
        _api.driveStatus(widget.spaceId),
      ]);
      if (!mounted) return;
      setState(() {
        _integrity = results[0] as IntegrityReport;
        _history = results[1] as List<BackupRun>;
        _drive = results[2] as DriveStatus;
        _loading = false;
      });
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
      if (!mounted) return;
      setState(() {
        _loading = false;
        _failed = true;
      });
    }
  }

  Future<void> _syncDrive() async {
    setState(() => _busySync = true);
    try {
      await _api.driveSync(widget.spaceId);
      _toast(NoticeLevel.success, 'DRIVE_SYNC', 'Drive sync started.');
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
    } finally {
      if (mounted) setState(() => _busySync = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Backups & data health')),
      body: Column(
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(16, 16, 16, 0),
            child: HelpCard(
              title: 'Backups and data health',
              user:
                  "Your documents are kept in three independent copies: live storage, a second-cloud mirror, and a human-browsable Google Drive. This screen shows whether the copies agree, your recent backup runs, and lets you sync to Drive now.",
              dev: null,
            ),
          ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: _load,
              child: _buildBody(scheme),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBody(ColorScheme scheme) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_failed) {
      return ListView(
        children: [
          const SizedBox(height: 80),
          Center(
            child: Text("Couldn't load data health. Pull to retry.",
                style: TextStyle(color: scheme.onSurfaceVariant),),
          ),
        ],
      );
    }
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _integrityCard(scheme),
        _historyCard(scheme),
        _driveCard(scheme),
      ],
    );
  }

  // ---- integrity ----------------------------------------------------------
  Widget _integrityCard(ColorScheme scheme) {
    final r = _integrity;
    if (r == null) return const SizedBox.shrink();
    final total = r.documents;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Integrity', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
            const SizedBox(height: 6),
            Text('$total document${total == 1 ? '' : 's'} tracked',
                style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),),
            const SizedBox(height: 12),
            _tierRow(scheme, 'Primary (R2)', '${r.primaryOk}/$total ok'),
            _tierRow(scheme, 'Sidecars', '${r.sidecarOk}/$total ok'),
            _tierRow(
              scheme,
              'Mirror (B2)',
              r.mirrorOk == null ? 'not configured' : '${r.mirrorOk}/$total ok',
            ),
            _tierRow(scheme, 'Drive', '${r.driveOk}/$total ok'),
            const Divider(height: 24),
            if (r.criticalCount > 0)
              Row(
                children: [
                  Icon(Icons.error_outline, size: 18, color: scheme.error),
                  const SizedBox(width: 8),
                  Text(
                    '${r.criticalCount} critical issue${r.criticalCount == 1 ? '' : 's'}',
                    style: TextStyle(color: scheme.error, fontWeight: FontWeight.w700),
                  ),
                ],
              )
            else
              Row(
                children: [
                  Icon(Icons.check_circle_outline, size: 18, color: scheme.primary),
                  const SizedBox(width: 8),
                  Text('All good',
                      style: TextStyle(color: scheme.primary, fontWeight: FontWeight.w700),),
                ],
              ),
          ],
        ),
      ),
    );
  }

  Widget _tierRow(ColorScheme scheme, String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label),
          const SizedBox(width: 12),
          // Flexible + ellipsis so a long server-provided value (e.g. the backup mode)
          // never overflows the row on a narrow screen.
          Flexible(
            child: Text(value,
                textAlign: TextAlign.right,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: scheme.onSurfaceVariant, fontWeight: FontWeight.w600),),
          ),
        ],
      ),
    );
  }

  // ---- history ------------------------------------------------------------
  Widget _historyCard(ColorScheme scheme) {
    final runs = _history ?? const <BackupRun>[];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Backup history',
                style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),),
            const SizedBox(height: 10),
            if (runs.isEmpty)
              Text('No backup runs recorded yet.',
                  style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),)
            else
              for (final run in runs)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(run.kind, style: const TextStyle(fontWeight: FontWeight.w600)),
                            if (run.finishedAt != null)
                              Text(run.finishedAt!,
                                  style: TextStyle(
                                      color: scheme.onSurfaceVariant, fontSize: 12,),),
                          ],
                        ),
                      ),
                      const SizedBox(width: 8),
                      _statusChip(scheme, run.status),
                    ],
                  ),
                ),
          ],
        ),
      ),
    );
  }

  Widget _statusChip(ColorScheme scheme, String status) {
    final color = switch (status.toLowerCase()) {
      'success' => Colors.green,
      'failed' => scheme.error,
      'running' => Colors.amber,
      _ => scheme.onSurfaceVariant,
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        status,
        style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w700),
      ),
    );
  }

  // ---- google drive -------------------------------------------------------
  Widget _driveCard(ColorScheme scheme) {
    final d = _drive;
    if (d == null) return const SizedBox.shrink();
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Google Drive',
                style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),),
            const SizedBox(height: 10),
            _tierRow(scheme, 'Connected', d.connected ? 'yes' : 'no'),
            _tierRow(scheme, 'Mode', d.mode),
            _tierRow(scheme, 'Connections', '${d.connectionCount}'),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton.icon(
                onPressed: _busySync ? null : _syncDrive,
                icon: const Icon(Icons.sync, size: 18),
                label: Text(_busySync ? 'Starting...' : 'Sync now'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
