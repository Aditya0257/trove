/// ============================================================================
///  AdminScreen - approvals + account management (admin-only)
/// ============================================================================
///
///  Purpose
///  -------
///  The one place an admin runs the vault's small user base: approve or reject the
///  accounts awaiting sign-off, and browse every account with a guarded delete that
///  demands the account email typed back before it wipes the account and all its
///  data. Deleting is deliberately hard - it is irreversible.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/models/account.dart';
import '../../core/notice/notice.dart';
import '../../core/notice/notice_center.dart';
import '../../ui/widgets/help_card.dart';
import 'admin_api.dart';

class AdminScreen extends ConsumerWidget {
  const AdminScreen({super.key});

  void _toast(NoticeLevel level, String code, String message) {
    NoticeCenter.instance
        .show(Notice.local(level: level, code: code, userMessage: message));
  }

  Future<void> _approve(WidgetRef ref, String id, String email) async {
    try {
      await ref.read(adminApiProvider).approve(id);
      ref.invalidate(adminPendingProvider);
      ref.invalidate(adminUsersProvider);
      _toast(NoticeLevel.success, 'USER_APPROVED', '$email has been approved.');
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
    }
  }

  Future<void> _reject(WidgetRef ref, String id, String email) async {
    try {
      await ref.read(adminApiProvider).reject(id);
      ref.invalidate(adminPendingProvider);
      ref.invalidate(adminUsersProvider);
      _toast(NoticeLevel.info, 'USER_REJECTED', "$email's request was rejected.");
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
    }
  }

  Future<void> _confirmDelete(
      BuildContext context, WidgetRef ref, AdminUser u,) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => _DeleteDialog(user: u),
    );
    if (ok != true) return;
    try {
      await ref.read(adminApiProvider).deleteUser(u.id, u.email);
      ref.invalidate(adminUsersProvider);
      _toast(NoticeLevel.success, 'USER_DELETED',
          '${u.email} and all its data have been deleted.',);
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final pending = ref.watch(adminPendingProvider);
    final users = ref.watch(adminUsersProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Admin')),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(adminPendingProvider);
          ref.invalidate(adminUsersProvider);
          await Future.wait([
            ref.read(adminPendingProvider.future),
            ref.read(adminUsersProvider.future),
          ]);
        },
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const HelpCard(
              title: 'Admin',
              user:
                  "Approve or decline people asking to join, and manage accounts. Deleting an account permanently removes it and all of its documents, so it asks you to type the email to confirm.",
              dev: null,
            ),
            _sectionTitle(scheme, 'Awaiting approval'),
            pending.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (e, _) => _errorLine(
                scheme, "Couldn't load pending accounts. Pull to refresh.",),
              data: (list) => list.isEmpty
                  ? _emptyLine(scheme, 'No accounts awaiting approval.')
                  : Column(
                      children: [
                        for (final u in list) _pendingCard(context, ref, u),
                      ],
                    ),
            ),
            const SizedBox(height: 16),
            _sectionTitle(scheme, 'All accounts'),
            users.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (e, _) => _errorLine(
                scheme, "Couldn't load accounts. Pull to refresh.",),
              data: (list) => list.isEmpty
                  ? _emptyLine(scheme, 'No accounts yet.')
                  : Column(
                      children: [
                        for (final u in list) _userCard(context, ref, scheme, u),
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _sectionTitle(ColorScheme scheme, String text) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text(
          text,
          style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 18),
        ),
      );

  Widget _emptyLine(ColorScheme scheme, String text) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Text(
          text,
          style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
        ),
      );

  Widget _errorLine(ColorScheme scheme, String text) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Text(
          text,
          style: TextStyle(color: scheme.error, fontSize: 13),
        ),
      );

  Widget _pendingCard(BuildContext context, WidgetRef ref, PendingUser u) {
    final scheme = Theme.of(context).colorScheme;
    final name = u.displayName.isNotEmpty ? u.displayName : u.email;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(name, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
            const SizedBox(height: 2),
            Text(u.email, style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13)),
            if (u.requestedAt != null) ...[
              const SizedBox(height: 2),
              Text('Requested ${u.requestedAt}',
                  style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),),
            ],
            const SizedBox(height: 12),
            Row(
              children: [
                FilledButton(
                  onPressed: () => _approve(ref, u.id, u.email),
                  child: const Text('Approve'),
                ),
                const SizedBox(width: 8),
                TextButton(
                  onPressed: () => _reject(ref, u.id, u.email),
                  child: const Text('Reject'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _userCard(
      BuildContext context, WidgetRef ref, ColorScheme scheme, AdminUser u,) {
    final name = u.displayName.isNotEmpty ? u.displayName : u.email;
    // A verified-but-not-yet-active account is awaiting the admin's approval. Offer the
    // approve/reject controls right here too, so an admin can always act on it even if
    // the "Awaiting approval" list above is momentarily stale.
    final awaitingApproval =
        !u.admin && (u.status == 'pending' || u.status == 'awaiting_approval');
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(name,
                          style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16),),
                      const SizedBox(height: 2),
                      Text(u.email,
                          style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),),
                    ],
                  ),
                ),
                if (!u.admin)
                  IconButton(
                    onPressed: () => _confirmDelete(context, ref, u),
                    icon: Icon(Icons.delete_outline, color: scheme.error),
                    tooltip: 'Delete account',
                  ),
              ],
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: [
                _chip(scheme, u.status.isNotEmpty ? u.status : 'unknown',
                    scheme.secondaryContainer, scheme.onSecondaryContainer,),
                if (u.admin)
                  _chip(scheme, 'admin', scheme.primaryContainer,
                      scheme.onPrimaryContainer,),
              ],
            ),
            if (awaitingApproval) ...[
              const SizedBox(height: 12),
              Row(
                children: [
                  FilledButton(
                    onPressed: () => _approve(ref, u.id, u.email),
                    child: const Text('Approve'),
                  ),
                  const SizedBox(width: 8),
                  TextButton(
                    onPressed: () => _reject(ref, u.id, u.email),
                    child: const Text('Reject'),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _chip(ColorScheme scheme, String label, Color bg, Color fg) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
        decoration: BoxDecoration(
          color: bg,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Text(
          label,
          style: TextStyle(color: fg, fontSize: 11, fontWeight: FontWeight.w700),
        ),
      );
}

/// Confirm-and-type-back dialog guarding a permanent account deletion. The Delete
/// button stays disabled until the typed text matches the account email
/// (case-insensitive), so the action cannot be a slip.
class _DeleteDialog extends StatefulWidget {
  const _DeleteDialog({required this.user});
  final AdminUser user;

  @override
  State<_DeleteDialog> createState() => _DeleteDialogState();
}

class _DeleteDialogState extends State<_DeleteDialog> {
  final _confirm = TextEditingController();

  @override
  void dispose() {
    _confirm.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final matches =
        _confirm.text.trim().toLowerCase() == widget.user.email.toLowerCase();
    return AlertDialog(
      title: const Text('Delete account'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'This permanently deletes the account and ALL its data. Type the email to confirm.',
          ),
          const SizedBox(height: 12),
          Text(
            widget.user.email,
            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _confirm,
            autofocus: true,
            keyboardType: TextInputType.emailAddress,
            decoration: const InputDecoration(labelText: 'Confirm email'),
            onChanged: (_) => setState(() {}),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: matches ? () => Navigator.of(context).pop(true) : null,
          style: FilledButton.styleFrom(backgroundColor: scheme.error),
          child: const Text('Delete'),
        ),
      ],
    );
  }
}
