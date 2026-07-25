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
        ],
      ),
    );
  }
}
