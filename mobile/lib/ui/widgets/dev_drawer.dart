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

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/notice/dev_log.dart';
import '../../core/notice/usage_overview.dart';

class DeveloperDrawer extends ConsumerStatefulWidget {
  const DeveloperDrawer({super.key});

  @override
  ConsumerState<DeveloperDrawer> createState() => _DeveloperDrawerState();
}

class _DeveloperDrawerState extends ConsumerState<DeveloperDrawer> {
  // When on, only failed calls (non-2xx or never-reached) are shown - a quick way to
  // spot what went wrong without scanning the whole trail.
  bool _errorsOnly = false;

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
              padding: const EdgeInsets.fromLTRB(16, 12, 8, 4),
              child: Row(
                children: [
                  const Text('Developer',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text('request trail',
                        style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),),
                  ),
                  IconButton(
                    tooltip: 'Close',
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.of(context).maybePop(),
                  ),
                ],
              ),
            ),
            // Filter + clear on their own labelled row, so the controls are obvious.
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 12, 8),
              child: Row(
                children: [
                  FilterChip(
                    label: const Text('Errors only'),
                    selected: _errorsOnly,
                    visualDensity: VisualDensity.compact,
                    onSelected: (v) => setState(() => _errorsOnly = v),
                  ),
                  const Spacer(),
                  TextButton.icon(
                    onPressed: () => DeveloperLog.instance.clear(),
                    icon: const Icon(Icons.delete_sweep_outlined, size: 18),
                    label: const Text('Clear'),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            const _AiUsageGauge(),
            const Divider(height: 1),
            Expanded(
              child: ListenableBuilder(
                listenable: DeveloperLog.instance,
                builder: (context, _) {
                  final all = DeveloperLog.instance.entries;
                  final entries = _errorsOnly
                      ? all.where((e) => !e.ok || !e.reachedServer).toList()
                      : all;
                  if (entries.isEmpty) {
                    return Center(
                      child: Text(
                        _errorsOnly
                            ? (all.isEmpty ? 'No requests yet.' : 'No errors - all calls succeeded.')
                            : 'No requests yet.',
                        style: TextStyle(color: scheme.onSurfaceVariant),
                      ),
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

/// Free-tier usage gauge: the two daily pools (AI, email) and the running-total
/// storage meters as clamped progress bars, refreshable in place. Degrades quietly -
/// never throws into the UI.
class _AiUsageGauge extends ConsumerWidget {
  const _AiUsageGauge();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final async = ref.watch(usageProvider);

    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 12),
      child: Container(
        padding: const EdgeInsets.fromLTRB(14, 12, 8, 14),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Text('Free-tier usage',
                    style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: scheme.onSurface,),),
                const Spacer(),
                IconButton(
                  visualDensity: VisualDensity.compact,
                  iconSize: 18,
                  tooltip: 'Refresh',
                  onPressed: () => ref.invalidate(usageProvider),
                  icon: Icon(Icons.refresh, color: scheme.onSurfaceVariant),
                ),
              ],
            ),
            async.when(
              data: (usage) => _content(usage, scheme),
              loading: () => Padding(
                padding: const EdgeInsets.only(top: 8, right: 6),
                child: LinearProgressIndicator(
                  minHeight: 6,
                  backgroundColor: scheme.surface,
                ),
              ),
              error: (_, __) => Padding(
                padding: const EdgeInsets.only(top: 6, right: 6),
                child: Text('Usage unavailable',
                    style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _content(UsageOverview u, ColorScheme scheme) {
    final reset = u.dailyResetAt;
    final resetLine = reset == null
        ? null
        : 'Resets 00:00 UTC · ${_istLabel(reset)} IST (in ${_resetIn(reset)})';
    return Padding(
      padding: const EdgeInsets.only(right: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // ── Daily pools ──────────────────────────────────────────────
          _row('AI · everyone today',
              used: u.ai.globalNeurons, limit: u.ai.limitNeurons,
              tokens: u.ai.globalTokens, scheme: scheme,),
          const SizedBox(height: 12),
          _row('AI · you today',
              used: u.ai.userNeurons, limit: u.ai.perUserLimitNeurons,
              tokens: u.ai.userTokens, scheme: scheme,),
          const SizedBox(height: 12),
          _row('Email today',
              used: u.email.sentToday, limit: u.email.dailyLimit,
              unit: 'emails', scheme: scheme,),
          if (resetLine != null)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(resetLine,
                  style: TextStyle(fontSize: 10.5, color: scheme.onSurfaceVariant),),
            ),
          if (u.email.reached)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                "Email limit reached - new emails won't send until "
                '${reset == null ? 'the daily reset' : '${_istLabel(reset)} IST'}. '
                'Reminders still appear in-app and as phone notifications.',
                style: TextStyle(fontSize: 10.5, color: scheme.error),
              ),
            ),
          const SizedBox(height: 14),
          // ── Running totals ───────────────────────────────────────────
          _storeRow('Object storage',
              used: u.storage.usedBytes, limit: u.storage.limitBytes, scheme: scheme,),
          const SizedBox(height: 12),
          _storeRow('Database',
              used: u.database.usedBytes, limit: u.database.limitBytes, scheme: scheme,),
          const SizedBox(height: 12),
          if (u.mirror.enabled)
            _storeRow('Mirror copy',
                used: u.mirror.usedBytes, limit: u.mirror.limitBytes, scheme: scheme,)
          else
            Text('Mirror copy: not configured.',
                style: TextStyle(
                    fontSize: 10.5,
                    fontStyle: FontStyle.italic,
                    color: scheme.onSurfaceVariant,),),
          const SizedBox(height: 10),
          Text(
            'Storage figures are app-wide (not per user), updated live. Database size is '
            'mostly fixed Postgres + search-extension baseline (~8-10 MB); your rows are only '
            'KB of text + metadata per document. Images and PDFs live in object storage, never '
            'the database.',
            style: TextStyle(fontSize: 10, color: scheme.onSurfaceVariant, height: 1.4),
          ),
        ],
      ),
    );
  }

  /// A running-total byte meter (used / limit), no daily reset.
  Widget _storeRow(String label,
      {required num used, required num limit, required ColorScheme scheme,}) {
    final progress = limit > 0 ? (used / limit).clamp(0.0, 1.0) : 0.0;
    final percent = limit > 0 ? (used / limit * 100).round() : 0;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Text(label,
                style: TextStyle(
                    fontSize: 12, fontWeight: FontWeight.w600, color: scheme.onSurface,),),
            const Spacer(),
            Text('${_bytes(used)} / ${_bytes(limit)}',
                style: TextStyle(
                    fontFamily: 'monospace', fontSize: 11, color: scheme.onSurfaceVariant,),),
          ],
        ),
        const SizedBox(height: 4),
        ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: LinearProgressIndicator(
            value: progress, minHeight: 6, backgroundColor: scheme.surface,),
        ),
        const SizedBox(height: 3),
        Text('$percent% used · running total, no daily reset',
            style: TextStyle(
                fontFamily: 'monospace', fontSize: 10.5, color: scheme.onSurfaceVariant,),),
      ],
    );
  }

  /// The reset instant rendered in IST (UTC+5:30, no DST), e.g. "5:30 AM".
  static String _istLabel(DateTime utc) {
    final ist = utc.toUtc().add(const Duration(hours: 5, minutes: 30));
    final ampm = ist.hour < 12 ? 'AM' : 'PM';
    var h = ist.hour % 12;
    if (h == 0) h = 12;
    return '$h:${ist.minute.toString().padLeft(2, '0')} $ampm';
  }

  /// Coarse time remaining until the reset (e.g. "6h 12m").
  static String _resetIn(DateTime utc) {
    final mins = utc.toUtc().difference(DateTime.now().toUtc()).inMinutes;
    if (mins <= 0) return 'now';
    final h = mins ~/ 60;
    final m = mins % 60;
    return h > 0 ? '${h}h ${m}m' : '${m}m';
  }

  /// Human bytes, e.g. 24.4 MB / 10.0 GB.
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

  /// A daily-pool meter (used / limit). `tokens` adds a sub-line (AI only); `unit`
  /// labels the numbers (credits for AI, emails for the email pool).
  Widget _row(
    String label, {
    required num used,
    required num limit,
    required ColorScheme scheme,
    num? tokens,
    String unit = 'credits',
  }) {
    final fmt = NumberFormat.compact();
    final progress = limit > 0 ? (used / limit).clamp(0.0, 1.0) : 0.0;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Text(label,
                style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: scheme.onSurface,),),
            const Spacer(),
            Text('${fmt.format(used)} / ${fmt.format(limit)} $unit',
                style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 11,
                    color: scheme.onSurfaceVariant,),),
          ],
        ),
        const SizedBox(height: 4),
        ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: LinearProgressIndicator(
            value: progress,
            minHeight: 6,
            backgroundColor: scheme.surface,
          ),
        ),
        if (tokens != null) ...[
          const SizedBox(height: 3),
          Text('${fmt.format(tokens)} tokens',
              style: TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 10.5,
                  color: scheme.onSurfaceVariant,),),
        ],
      ],
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
    final m = _meaningFor(entry.method, entry.path);

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
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(m.label, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
            Text(
              '${entry.durationMs}ms'
              '${entry.requestId != null ? '  ·  req ${entry.requestId}' : ''}',
              style: TextStyle(
                  fontFamily: 'monospace', fontSize: 11, color: scheme.onSurfaceVariant,),
            ),
          ],
        ),
      ),
      children: [
        _kv('you', m.user, scheme),
        _kv('dev', m.dev, scheme),
        if (m.business.isNotEmpty && m.business != '-') _kv('biz', m.business, scheme),
        _kv('flow', m.flow, scheme),
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
        if (entry.body != null) ...[
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerLeft,
            child: Text('response body',
                style: TextStyle(
                    fontSize: 11, fontWeight: FontWeight.w700, color: scheme.onSurfaceVariant,),),
          ),
          const SizedBox(height: 4),
          Container(
            width: double.infinity,
            constraints: const BoxConstraints(maxHeight: 260),
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: scheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(8),
            ),
            child: SingleChildScrollView(
              child: SelectableText(
                _prettyBody(entry.body!),
                style: const TextStyle(fontFamily: 'monospace', fontSize: 11.5, height: 1.4),
              ),
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

/// Three-lens meaning for a call plus its backend flow - the mobile twin of the web
/// drawer's registry. Short by design: read the drawer and understand what happened.
typedef _Meaning = ({String label, String user, String dev, String business, String flow});

_Meaning _meaningFor(String method, String path) {
  final p = path.split('?').first;
  final m = method.toUpperCase();
  const a = 'Flutter';
  _Meaning mk(String label, String user, String dev, String business, String flow) =>
      (label: label, user: user, dev: dev, business: business, flow: flow);

  if (p == '/api/auth/login') {
    return mk('Sign in', 'Signing you in', 'verify credentials, mint a JWT', 'gate to a private vault', '$a -> AuthController.login() -> UserService + JwtService');
  }
  if (p == '/api/auth/register') {
    return mk('Create account', 'Creating your account', 'create user (unverified) + personal space, email an OTP', 'a new owner joins', '$a -> AuthController.register() -> UserService.register()');
  }
  if (p == '/api/auth/verify-email') {
    return mk('Verify email', 'Confirming your email', 'check the OTP, then apply the admin-approval gate', 'only real, reachable emails get in', '$a -> AuthController.verifyEmail() -> EmailVerificationService + UserService');
  }
  if (p == '/api/account/me') {
    return mk('Your profile', 'Loading your profile', 'profile + 2FA + avatar summary', 'account self-service', '$a -> AccountController.me()');
  }
  if (p == '/api/account/password') {
    return mk('Change password', 'Updating your password', 're-check current, BCrypt the new one', 'account security', '$a -> AccountController.changePassword() -> UserService');
  }
  if (p.startsWith('/api/account/email')) {
    return mk('Change email', 'Changing your email', 'OTP to the new address, swap on confirm', 'keep a reachable email', '$a -> AccountController.startEmailChange()/verify');
  }
  if (p.startsWith('/api/account/photo')) {
    return mk('Profile photo', 'Saving your photo', 'store the avatar in object storage (R2)', 'a friendly profile', '$a -> AccountController.uploadPhoto()/deletePhoto()');
  }
  if (p.startsWith('/api/account/2fa')) {
    return mk('Two-factor', 'Managing two-factor', 'TOTP secret AES-GCM encrypted at rest', 'a second sign-in factor', '$a -> AccountController.2fa*()');
  }
  if (p == '/api/admin/users') {
    return mk('All accounts', 'Loading accounts', 'every account for the admin', 'closed-circle control', '$a -> AdminController.users()');
  }
  if (p == '/api/admin/pending') {
    return mk('Approvals', 'Loading approvals', 'accounts awaiting approval', 'nobody in until the admin says so', '$a -> AdminController.pending()');
  }
  if (RegExp(r'^/api/admin/users/[^/]+/delete$').hasMatch(p)) {
    return mk('Delete account', 'Deleting an account', 'purge R2 + Drive + index, then the row', 'the one destructive, guarded op', '$a -> AdminController.deleteUser() -> AccountDeletionService');
  }
  if (p.startsWith('/api/admin/users/')) {
    return mk('Approve / reject', 'Updating an account', 'approve or decline a sign-up', 'gate the private circle', '$a -> AdminController.approve()/reject()');
  }
  if (p == '/api/spaces') {
    return mk('Your spaces', 'Loading your spaces', 'personal + shared spaces you belong to', 'who can see which documents', '$a -> SpaceController.mine() -> SpaceService');
  }
  if (p == '/api/spaces/invitations') {
    return mk('Invitations', 'Loading invitations', 'pending space invitations for you', 'join a shared space', '$a -> SpaceController.invitations()');
  }
  if (p.contains('/ingest-address')) {
    return mk('Ingest address', 'Your forward-to-file address', 'per-space unguessable ingest token', 'forward a bill and it self-files', '$a -> IngestTokenController');
  }
  if (p.startsWith('/api/spaces/')) {
    return mk('Space', 'Managing a space', 'members, roles, join links', 'shared-space administration', '$a -> SpaceController');
  }
  if (p == '/api/categories') {
    return mk('Categories', 'Loading categories', 'global + space category taxonomy', 'how the vault is organised', '$a -> CategoryController.list()');
  }
  if (p == '/api/search') {
    return mk('Search', 'Finding your documents', 'NL query -> parse -> filtered DB query with a limit', 'plain-English retrieval', '$a -> SearchController.search() -> SearchService');
  }
  if (p == '/api/chat/ask') {
    return mk('Ask your vault', 'Answering from your documents', 'normalize -> embed -> retrieve -> grounded LLM answer', 'ask in plain language', '$a -> ChatController.ask() -> VaultChatService');
  }
  if (p == '/api/chat/reindex') {
    return mk('Re-index', 'Rebuilding the search index', 'embed documents that are not yet indexed', 'keep search fresh', '$a -> ChatController.reindex()');
  }
  if (p == '/api/mail') {
    return mk('Mail', 'Loading your email threads', 'one page of bundles grouped in the DB + facets', 'file important emails as threads', '$a -> MailController.list() -> MailService.bundles()');
  }
  if (p == '/api/documents/mail-bundle') {
    return mk('Mail thread', 'Opening this email thread', "one bundle's emails, oldest first", 'read a filed email thread', '$a -> DocumentController.mailBundle()');
  }
  if (p == '/api/documents/trash') {
    return mk('Trash', 'Loading Trash', 'soft-deleted documents (30-day window)', 'recover an accidental delete', '$a -> DocumentController.trash()');
  }
  if (RegExp(r'^/api/documents/[^/]+/restore$').hasMatch(p)) {
    return mk('Restore', 'Restoring a document', 'move it back to the live vault', 'undo a delete', '$a -> DocumentController.restore()');
  }
  if (RegExp(r'^/api/documents/[^/]+/purge$').hasMatch(p)) {
    return mk('Delete forever', 'Permanently deleting', 'remove from live R2 + Drive + DB', 'clear it for good', '$a -> DocumentController.purge()');
  }
  if (RegExp(r'^/api/documents/[^/]+/confirm$').hasMatch(p)) {
    return mk('Confirm a document', 'Saving your reviewed details', 'human-review -> confirmed; fires reminders + anomaly', 'nothing is trusted until confirmed', '$a -> DocumentController.confirm()');
  }
  if (RegExp(r'^/api/documents/[^/]+/content$').hasMatch(p)) {
    return mk('Open a vital file', 'Opening your file', 'decrypt-stream the encrypted bytes', 'sensitive PII stays encrypted at rest', '$a -> DocumentController.content()');
  }
  if (RegExp(r'^/api/documents/[^/]+/reextract$').hasMatch(p)) {
    return mk('Read again with AI', 'Re-reading this document', 'reset confidence + re-dispatch extraction', 'retry a read that timed out', '$a -> DocumentController.reextract() -> DocumentService');
  }
  if (RegExp(r'^/api/documents/[^/]+/related$').hasMatch(p)) {
    return mk('Related documents', 'Loading related documents', 'same merchant, else same category, newest first', 'auto-linking related docs', '$a -> DocumentController.related() -> DocumentService.related()');
  }
  if (p == '/api/documents' && m == 'POST') {
    return mk('Upload a document', 'Saving your document', 'store in R2 + sidecar; async extraction queued', 'an item enters the vault', '$a -> DocumentController.upload() -> DocumentService + ExtractionProvider');
  }
  if (p == '/api/documents' && m == 'GET') {
    return mk('List documents', 'Loading your documents', 'one page of the rebuildable index', 'browse the vault', '$a -> DocumentController.list() -> DocumentService.listPaged()');
  }
  if (RegExp(r'^/api/documents/[^/]+$').hasMatch(p)) {
    if (m == 'DELETE') {
      return mk('Move to Trash', 'Moving to Trash', 'soft delete; file moved to a _trash prefix', 'recoverable for 30 days', '$a -> DocumentController.delete()');
    }
    return mk('Fetch a document', 'Loading a document', 'index row + a presigned view URL', 'reads the rebuildable index', '$a -> DocumentController.get()');
  }
  if (p == '/api/reminders' && m == 'GET') {
    return mk('Reminders', 'Loading reminders', 'pending reminders, soonest first, with linked file names', 'never miss a due date / warranty', '$a -> ReminderController.list()');
  }
  if (p.startsWith('/api/reminders')) {
    return mk('Reminder', 'Updating a reminder', 'create / done / snooze / dismiss', 'stay on top of dates', '$a -> ReminderController');
  }
  if (p == '/api/spend/by-month') {
    return mk('Spend by month', 'Loading monthly spend', 'aggregate confirmed documents by month', 'see the trend over time', '$a -> SpendController.byMonth()');
  }
  if (p.startsWith('/api/spend')) {
    return mk('Spend', 'Loading your spend', 'aggregate confirmed documents by category', 'understand where money goes', '$a -> SpendController.summary()');
  }
  if (p == '/api/insights/expiring') {
    return mk('Expiring soon', 'Loading what is coming up', 'due dates + warranties in the window, minus ones handled in Reminders', 'act before something lapses', '$a -> InsightsController.expiring() -> InsightsService');
  }
  if (p == '/api/insights/recurring') {
    return mk('Recurring', 'Finding your subscriptions', 'group confirmed docs by merchant+category; infer cadence + predict next', 'spot what recurs', '$a -> InsightsController.recurring() -> InsightsService');
  }
  if (p.startsWith('/api/integrations/google-drive')) {
    return mk('Google Drive', 'Talking to Google Drive', 'per-owner OAuth backup / sync', 'human-navigable third copy', '$a -> DriveController');
  }
  if (p.startsWith('/api/integrity')) {
    return mk('Data health', 'Checking your backups', 'verify the tiers agree; recent runs', 'proof the copies are intact', '$a -> IntegrityController');
  }
  return mk('API request', 'Working...', '$m $p', '-', '$a -> $m $p');
}

/// Pretty-prints a response body: indented JSON for maps/lists, the string as-is
/// for a truncated preview, else toString.
String _prettyBody(Object body) {
  if (body is String) return body;
  try {
    return const JsonEncoder.withIndent('  ').convert(body);
  } catch (_) {
    return body.toString();
  }
}
