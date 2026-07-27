/// ============================================================================
///  SpaceManageScreen - members, invitations and the join link for a space
/// ============================================================================
///  Purpose:  the owner's controls for a shared space: see members, invite by
///            email, approve join requests, and share a join link. Owner-only
///            actions are enforced by the backend (a 403 surfaces as a notice).
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/models/drive.dart';
import '../../core/models/membership.dart';
import '../../core/models/space.dart';
import '../../core/notice/notice.dart';
import '../../core/notice/notice_center.dart';
import '../../ui/widgets/help_card.dart';
import 'spaces_api.dart';

class SpaceManageScreen extends ConsumerStatefulWidget {
  const SpaceManageScreen({super.key, required this.space});
  final Space space;

  @override
  ConsumerState<SpaceManageScreen> createState() => _SpaceManageScreenState();
}

class _SpaceManageScreenState extends ConsumerState<SpaceManageScreen> {
  final _email = TextEditingController();
  String _role = 'member';
  String? _joinUrl;
  bool _busy = false;
  String? _ingestAddress;
  bool _ingestBusy = false;
  bool _driveBusy = false;

  String get _spaceId => widget.space.id;

  @override
  void dispose() {
    _email.dispose();
    super.dispose();
  }

  Future<void> _invite() async {
    if (_email.text.trim().isEmpty) return;
    setState(() => _busy = true);
    try {
      await ref.read(spacesApiProvider).addMember(_spaceId, _email.text, _role);
      _email.clear();
      NoticeCenter.instance.show(Notice.local(
        level: NoticeLevel.success, code: 'INVITED',
        userMessage: 'Invitation sent, waiting for them to accept.',
      ),);
      ref.invalidate(membersProvider(_spaceId));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _approve(Member m) async {
    await ref.read(spacesApiProvider).approveMember(_spaceId, m.userId);
    ref.invalidate(membersProvider(_spaceId));
  }

  Future<void> _getJoinLink() async {
    final url = await ref.read(spacesApiProvider).joinLink(_spaceId);
    if (mounted) setState(() => _joinUrl = url);
  }

  Future<void> _loadIngestAddress() async {
    setState(() => _ingestBusy = true);
    try {
      final address = await ref.read(spacesApiProvider).ingestAddress(_spaceId);
      if (mounted) setState(() => _ingestAddress = address);
    } catch (_) {
      // The API client already surfaces failures (incl. owner-only 403) via the Notice System.
    } finally {
      if (mounted) setState(() => _ingestBusy = false);
    }
  }

  Future<void> _rotateIngestAddress() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Rotate address?'),
        content: const Text(
          'Rotating creates a new address and immediately invalidates the old one. '
          'Anything still forwarding to the old address will stop filing here.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Rotate')),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _ingestBusy = true);
    try {
      final address = await ref.read(spacesApiProvider).rotateIngestAddress(_spaceId);
      if (mounted) {
        setState(() => _ingestAddress = address);
        NoticeCenter.instance.show(Notice.local(
          level: NoticeLevel.success, code: 'ADDRESS_ROTATED',
          userMessage: 'New address ready. The old one no longer works.',
        ),);
      }
    } catch (_) {
      // The API client already surfaces failures via the Notice System.
    } finally {
      if (mounted) setState(() => _ingestBusy = false);
    }
  }

  Future<void> _syncDrive() async {
    setState(() => _driveBusy = true);
    try {
      final r = await ref.read(spacesApiProvider).driveSync(_spaceId);
      ref.invalidate(driveStatusProvider(_spaceId));
      NoticeCenter.instance.show(Notice.local(
        level: NoticeLevel.success,
        code: 'DRIVE_SYNCED',
        userMessage: 'Backed up ${r.synced} document${r.synced == 1 ? '' : 's'}'
            '${r.skipped > 0 ? ' (${r.skipped} already up to date)' : ''}.',
      ),);
    } catch (_) {
      // surfaced by the notice interceptor
    } finally {
      if (mounted) setState(() => _driveBusy = false);
    }
  }

  Future<void> _disconnectDrive(DriveConnection c) async {
    final label = c.googleAccountName ?? c.googleEmail;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Disconnect Drive?'),
        content: Text('"$label" will stop backing up this space. Files already copied '
            'there stay; nothing is deleted.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Disconnect')),
        ],
      ),
    );
    if (ok != true) return;
    setState(() => _driveBusy = true);
    try {
      await ref.read(spacesApiProvider).driveDisconnect(_spaceId, c.id);
      ref.invalidate(driveStatusProvider(_spaceId));
      NoticeCenter.instance.show(Notice.local(
        level: NoticeLevel.info, code: 'DRIVE_DISCONNECTED', userMessage: 'Drive disconnected.',
      ),);
    } catch (_) {
      // surfaced by the notice interceptor
    } finally {
      if (mounted) setState(() => _driveBusy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final members = ref.watch(membersProvider(_spaceId));

    return Scaffold(
      appBar: AppBar(title: Text('Manage ${widget.space.name}')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const HelpCard(
            title: 'Managing a shared space',
            user:
                "Invite people to this space and set what they can do: owner (full control), member (add and edit documents), or viewer (read only). A document always belongs to exactly one space.",
            dev: null,
          ),
          Text('Members', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          members.when(
            loading: () => const Padding(
              padding: EdgeInsets.symmetric(vertical: 20),
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (_, __) => Text('Members are owner-only, or could not load.',
                style: TextStyle(color: scheme.onSurfaceVariant),),
            data: (list) => Column(
              children: [
                for (final m in list)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(m.name),
                    subtitle: Text(m.isActive
                        ? m.role
                        : (m.selfRequested ? 'wants to join' : '${m.status} - ${m.role}'),),
                    trailing: (m.isPending && m.selfRequested)
                        ? TextButton(onPressed: () => _approve(m), child: const Text('Approve'))
                        : null,
                  ),
              ],
            ),
          ),
          const Divider(height: 32),
          Text('Invite by email', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          TextField(
            controller: _email,
            keyboardType: TextInputType.emailAddress,
            decoration: const InputDecoration(labelText: 'Email', hintText: 'name@example.com'),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              DropdownButton<String>(
                value: _role,
                items: const [
                  DropdownMenuItem(value: 'member', child: Text('Member')),
                  DropdownMenuItem(value: 'viewer', child: Text('Viewer')),
                  DropdownMenuItem(value: 'owner', child: Text('Owner')),
                ],
                onChanged: (v) => setState(() => _role = v ?? 'member'),
              ),
              const Spacer(),
              FilledButton(
                onPressed: _busy ? null : _invite,
                child: const Text('Invite'),
              ),
            ],
          ),
          const Divider(height: 32),
          Text('Join link', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 4),
          Text(
            'Share this link so someone can request to join. You approve them above. '
            'It never adds anyone on its own.',
            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
          ),
          const SizedBox(height: 10),
          if (_joinUrl == null)
            OutlinedButton.icon(
              onPressed: _getJoinLink,
              icon: const Icon(Icons.link),
              label: const Text('Get join link'),
            )
          else
            Row(
              children: [
                Expanded(
                  child: SelectableText(_joinUrl!,
                      style: const TextStyle(fontSize: 13),),
                ),
                IconButton(
                  tooltip: 'Copy',
                  icon: const Icon(Icons.copy),
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: _joinUrl!));
                    NoticeCenter.instance.show(Notice.local(
                      level: NoticeLevel.success, code: 'LINK_COPIED',
                      userMessage: 'Join link copied.',
                    ),);
                  },
                ),
              ],
            ),
          const SizedBox(height: 20),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Forward-to-file address',
                      style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),),
                  const SizedBox(height: 6),
                  Text(
                    'Forward or share a bill to this address and it files itself into this space.',
                    style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
                  ),
                  const SizedBox(height: 10),
                  if (_ingestAddress == null)
                    OutlinedButton.icon(
                      onPressed: _ingestBusy ? null : _loadIngestAddress,
                      icon: const Icon(Icons.alternate_email),
                      label: Text(_ingestBusy ? 'Loading...' : 'Show address'),
                    )
                  else ...[
                    Row(
                      children: [
                        Expanded(
                          child: SelectableText(_ingestAddress!,
                              style: const TextStyle(fontSize: 13),),
                        ),
                        IconButton(
                          tooltip: 'Copy',
                          icon: const Icon(Icons.copy),
                          onPressed: () {
                            Clipboard.setData(ClipboardData(text: _ingestAddress!));
                            NoticeCenter.instance.show(Notice.local(
                              level: NoticeLevel.success, code: 'ADDRESS_COPIED',
                              userMessage: 'Address copied.',
                            ),);
                          },
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: TextButton.icon(
                        onPressed: _ingestBusy ? null : _rotateIngestAddress,
                        icon: const Icon(Icons.refresh, size: 18),
                        label: const Text('Rotate'),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 20),
          _driveCard(scheme),
        ],
      ),
    );
  }

  Widget _driveCard(ColorScheme scheme) {
    final status = ref.watch(driveStatusProvider(_spaceId));
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Expanded(
                  child: Text('Google Drive backup',
                      style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),),
                ),
                if (_driveBusy)
                  const SizedBox(
                      width: 16, height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              'A human-browsable third copy in your own Drive. Connect a Drive, and choose '
              'rotate vs mirror, from the web app; here you can see status, sync now, and disconnect.',
              style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
            ),
            const SizedBox(height: 12),
            status.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (_, __) => Text('Drive status could not be loaded.',
                  style: TextStyle(color: scheme.onSurfaceVariant),),
              data: (s) => _driveBody(s, scheme),
            ),
          ],
        ),
      ),
    );
  }

  Widget _driveBody(DriveStatus s, ColorScheme scheme) {
    if (!s.connected || s.connections.isEmpty) {
      return Text('No Drive connected yet. Open the web app to connect one.',
          style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),);
    }
    final fmt = DateFormat('d MMM yyyy, h:mm a');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final c in s.connections) ...[
          Row(
            children: [
              Expanded(
                child: Text(
                    c.googleAccountName?.isNotEmpty == true
                        ? '${c.googleAccountName} (${c.googleEmail})'
                        : c.googleEmail,
                    style: const TextStyle(fontWeight: FontWeight.w600),),
              ),
              if (c.active)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: scheme.primaryContainer,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text('ACTIVE',
                      style: TextStyle(
                          fontSize: 10, fontWeight: FontWeight.w700,
                          color: scheme.onPrimaryContainer,),),
                ),
            ],
          ),
          const SizedBox(height: 2),
          Text(
            c.lastSyncAt == null
                ? 'Not synced yet'
                : 'Last sync ${fmt.format(c.lastSyncAt!.toLocal())}',
            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
          ),
          if (c.troveBytes != null && c.storageLimitBytes != null)
            Text(
              'Trove ${_bytes(c.troveBytes!)} · ${_bytes(c.storageUsageBytes ?? 0)} of '
              '${_bytes(c.storageLimitBytes!)} used',
              style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
            ),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: _driveBusy ? null : () => _disconnectDrive(c),
              icon: const Icon(Icons.link_off, size: 18),
              label: const Text('Disconnect'),
            ),
          ),
          const Divider(height: 16),
        ],
        Align(
          alignment: Alignment.centerLeft,
          child: FilledButton.icon(
            onPressed: _driveBusy ? null : _syncDrive,
            icon: const Icon(Icons.sync, size: 18),
            label: const Text('Sync now'),
          ),
        ),
        if (s.connections.length > 1)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text('Mode: ${s.mode}. Change rotate/mirror from the web app.',
                style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),),
          ),
      ],
    );
  }

  static String _bytes(num n) {
    if (n < 1024) return '${n.round()} B';
    const units = ['KB', 'MB', 'GB', 'TB'];
    double v = n / 1024;
    var i = 0;
    while (v >= 1024 && i < units.length - 1) {
      v /= 1024;
      i++;
    }
    return '${v.toStringAsFixed(v < 10 ? 1 : 0)} ${units[i]}';
  }
}
