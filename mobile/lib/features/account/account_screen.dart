/// ============================================================================
///  AccountScreen - self-service profile + security (mirror of the web /account)
/// ============================================================================
///
///  Purpose
///  -------
///  One place for the signed-in user to manage their identity (photo, display name,
///  email), their password, and authenticator-app two-factor. Deleting an account is
///  deliberately not here - it is an admin-only action on the Admin screen.
/// ============================================================================
library;

import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/auth/auth_controller.dart';
import '../../core/models/account.dart';
import '../../core/notice/notice.dart';
import '../../core/notice/notice_center.dart';
import 'account_api.dart';

class AccountScreen extends ConsumerWidget {
  const AccountScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profile = ref.watch(accountProfileProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Your profile')),
      body: profile.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text(
              "Couldn't load your profile. Pull back and try again.",
              textAlign: TextAlign.center,
              style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
            ),
          ),
        ),
        data: (p) => _AccountBody(profile: p),
      ),
    );
  }
}

class _AccountBody extends ConsumerStatefulWidget {
  const _AccountBody({required this.profile});
  final AccountProfile profile;

  @override
  ConsumerState<_AccountBody> createState() => _AccountBodyState();
}

class _AccountBodyState extends ConsumerState<_AccountBody> {
  late final TextEditingController _name =
      TextEditingController(text: widget.profile.displayName);
  final _curPw = TextEditingController();
  final _newPw = TextEditingController();
  final _confirmPw = TextEditingController();
  final _newEmail = TextEditingController();
  final _emailPw = TextEditingController();
  final _emailCode = TextEditingController();
  final _twoFaCode = TextEditingController();

  bool _busyName = false;
  bool _busyPhoto = false;
  bool _busyPw = false;
  bool _busyEmail = false;
  bool _busy2fa = false;
  // Each password field owns its own reveal flag so toggling one never exposes another.
  bool _showCurPw = false;
  bool _showNewPw = false;
  bool _showConfirmPw = false;
  bool _showEmailPw = false;
  // 'idle' | 'form' | 'code'
  String _emailStep = 'idle';
  Map<String, String>? _twoFaSetup;

  AccountApi get _api => ref.read(accountApiProvider);
  AccountProfile get _p => widget.profile;

  @override
  void dispose() {
    _name.dispose();
    _curPw.dispose();
    _newPw.dispose();
    _confirmPw.dispose();
    _newEmail.dispose();
    _emailPw.dispose();
    _emailCode.dispose();
    _twoFaCode.dispose();
    super.dispose();
  }

  void _toast(NoticeLevel level, String code, String message) {
    NoticeCenter.instance.show(Notice.local(level: level, code: code, userMessage: message));
  }

  /// The trailing eye that reveals/hides a single password field.
  Widget _revealButton({required bool shown, required VoidCallback onToggle}) {
    return IconButton(
      icon: Icon(shown ? Icons.visibility_off_outlined : Icons.visibility_outlined),
      tooltip: shown ? 'Hide password' : 'Show password',
      onPressed: onToggle,
    );
  }

  Future<void> _guard(Future<void> Function() action, void Function(bool) setBusy) async {
    setBusy(true);
    try {
      await action();
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
    } finally {
      if (mounted) setBusy(false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return RefreshIndicator(
      onRefresh: () async => ref.refresh(accountProfileProvider.future),
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _profileCard(scheme),
          _emailCard(scheme),
          _passwordCard(scheme),
          _twoFactorCard(scheme),
          _sessionCard(scheme),
        ],
      ),
    );
  }

  // ---- profile: photo + name ---------------------------------------------
  Widget _profileCard(ColorScheme scheme) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                _avatar(scheme, 64),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Wrap(
                        spacing: 8,
                        children: [
                          OutlinedButton.icon(
                            onPressed: _busyPhoto ? null : _pickPhoto,
                            icon: const Icon(Icons.photo_camera_outlined, size: 18),
                            label: Text(_p.avatarUrl != null ? 'Change photo' : 'Add photo'),
                          ),
                          if (_p.avatarUrl != null)
                            TextButton(
                              onPressed: _busyPhoto ? null : _removePhoto,
                              child: const Text('Remove'),
                            ),
                        ],
                      ),
                      Text(
                        'A square image under 2 MB works best.',
                        style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _name,
              decoration: const InputDecoration(labelText: 'Display name'),
            ),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                onPressed: _busyName ? null : _saveName,
                child: Text(_busyName ? 'Saving...' : 'Save'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _avatar(ColorScheme scheme, double size) {
    final url = _p.avatarUrl;
    if (url != null) {
      return CircleAvatar(radius: size / 2, backgroundImage: NetworkImage(url));
    }
    final name = _p.displayName.isNotEmpty ? _p.displayName : _p.email;
    final initials = name.trim().isEmpty
        ? '?'
        : name.trim().split(RegExp(r'\s+')).map((w) => w[0]).take(2).join().toUpperCase();
    return CircleAvatar(
      radius: size / 2,
      backgroundColor: scheme.primaryContainer,
      child: Text(initials, style: TextStyle(color: scheme.onPrimaryContainer, fontWeight: FontWeight.w700)),
    );
  }

  Future<void> _pickPhoto() async {
    final picked = await ImagePicker().pickImage(source: ImageSource.gallery, imageQuality: 85);
    if (picked == null) return;
    await _guard(() async {
      await _api.uploadPhoto(File(picked.path));
      ref.invalidate(accountProfileProvider);
      _toast(NoticeLevel.success, 'PHOTO_SET', 'Profile photo updated.');
    }, (b) => setState(() => _busyPhoto = b),);
  }

  Future<void> _removePhoto() async {
    await _guard(() async {
      await _api.deletePhoto();
      ref.invalidate(accountProfileProvider);
      _toast(NoticeLevel.info, 'PHOTO_OFF', 'Profile photo removed.');
    }, (b) => setState(() => _busyPhoto = b),);
  }

  Future<void> _saveName() async {
    final name = _name.text.trim();
    if (name.isEmpty) {
      _toast(NoticeLevel.warning, 'NAME_EMPTY', 'Display name cannot be empty.');
      return;
    }
    await _guard(() async {
      await _api.updateDisplayName(name);
      ref.invalidate(accountProfileProvider);
      _toast(NoticeLevel.success, 'NAME_SAVED', 'Your name has been updated.');
    }, (b) => setState(() => _busyName = b),);
  }

  // ---- email change (OTP) -------------------------------------------------
  Widget _emailCard(ColorScheme scheme) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Email', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
            const SizedBox(height: 6),
            Text(
              'Your sign-in email is ${_p.email}. We send codes, resets and reminders here.',
              style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
            ),
            if (_p.pendingEmail != null) ...[
              const SizedBox(height: 6),
              Text('A change to ${_p.pendingEmail} is awaiting its code.',
                  style: TextStyle(color: scheme.tertiary, fontSize: 13),),
            ],
            const SizedBox(height: 10),
            if (_emailStep == 'idle')
              OutlinedButton(onPressed: () => setState(() => _emailStep = 'form'), child: const Text('Change email'))
            else if (_emailStep == 'form') ...[
              TextField(
                controller: _newEmail,
                keyboardType: TextInputType.emailAddress,
                decoration: const InputDecoration(labelText: 'New email'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _emailPw,
                obscureText: !_showEmailPw,
                decoration: InputDecoration(
                  labelText: 'Current password',
                  suffixIcon: _revealButton(
                    shown: _showEmailPw,
                    onToggle: () => setState(() => _showEmailPw = !_showEmailPw),
                  ),
                ),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  FilledButton(
                    onPressed: _busyEmail ? null : _startEmailChange,
                    child: Text(_busyEmail ? 'Sending...' : 'Send code'),
                  ),
                  const SizedBox(width: 8),
                  TextButton(onPressed: _cancelEmail, child: const Text('Cancel')),
                ],
              ),
            ] else ...[
              Text('Enter the code we sent to ${_newEmail.text.trim()}.',
                  style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),),
              const SizedBox(height: 8),
              TextField(
                controller: _emailCode,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: '6-digit code'),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  FilledButton(
                    onPressed: _busyEmail ? null : _verifyEmailChange,
                    child: Text(_busyEmail ? 'Confirming...' : 'Confirm new email'),
                  ),
                  const SizedBox(width: 8),
                  TextButton(onPressed: _cancelEmail, child: const Text('Cancel')),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _startEmailChange() async {
    if (_newEmail.text.trim().isEmpty || _emailPw.text.isEmpty) {
      _toast(NoticeLevel.warning, 'EMAIL_FIELDS', 'Enter the new email and your current password.');
      return;
    }
    await _guard(() async {
      await _api.startEmailChange(_newEmail.text, _emailPw.text);
      _emailPw.clear();
      setState(() => _emailStep = 'code');
      _toast(NoticeLevel.info, 'EMAIL_CODE', 'We sent a code to the new address.');
    }, (b) => setState(() => _busyEmail = b),);
  }

  Future<void> _verifyEmailChange() async {
    await _guard(() async {
      await _api.verifyEmailChange(_emailCode.text);
      ref.invalidate(accountProfileProvider);
      _newEmail.clear();
      _emailCode.clear();
      setState(() => _emailStep = 'idle');
      _toast(NoticeLevel.success, 'EMAIL_SET', 'Your sign-in email has been updated.');
    }, (b) => setState(() => _busyEmail = b),);
  }

  void _cancelEmail() {
    _newEmail.clear();
    _emailPw.clear();
    _emailCode.clear();
    setState(() => _emailStep = 'idle');
  }

  // ---- password -----------------------------------------------------------
  Widget _passwordCard(ColorScheme scheme) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Password', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
            const SizedBox(height: 10),
            TextField(
              controller: _curPw,
              obscureText: !_showCurPw,
              decoration: InputDecoration(
                labelText: 'Current password',
                suffixIcon: _revealButton(
                  shown: _showCurPw,
                  onToggle: () => setState(() => _showCurPw = !_showCurPw),
                ),
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _newPw,
              obscureText: !_showNewPw,
              decoration: InputDecoration(
                labelText: 'New password (at least 8 characters)',
                suffixIcon: _revealButton(
                  shown: _showNewPw,
                  onToggle: () => setState(() => _showNewPw = !_showNewPw),
                ),
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _confirmPw,
              obscureText: !_showConfirmPw,
              decoration: InputDecoration(
                labelText: 'Confirm new password',
                suffixIcon: _revealButton(
                  shown: _showConfirmPw,
                  onToggle: () => setState(() => _showConfirmPw = !_showConfirmPw),
                ),
              ),
            ),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                onPressed: _busyPw ? null : _changePassword,
                child: Text(_busyPw ? 'Updating...' : 'Update password'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _changePassword() async {
    if (_newPw.text.length < 8) {
      _toast(NoticeLevel.warning, 'PW_SHORT', 'The new password must be at least 8 characters.');
      return;
    }
    if (_newPw.text != _confirmPw.text) {
      _toast(NoticeLevel.warning, 'PW_MISMATCH', 'The new passwords do not match.');
      return;
    }
    await _guard(() async {
      await _api.changePassword(_curPw.text, _newPw.text);
      _curPw.clear();
      _newPw.clear();
      _confirmPw.clear();
      _toast(NoticeLevel.success, 'PW_CHANGED', 'Your password has been changed.');
    }, (b) => setState(() => _busyPw = b),);
  }

  // ---- two-factor ---------------------------------------------------------
  Widget _twoFactorCard(ColorScheme scheme) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Two-factor authentication',
                style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),),
            const SizedBox(height: 6),
            Text(
              'A 6-digit code from an authenticator app, in addition to your password.',
              style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
            ),
            const SizedBox(height: 10),
            if (_p.twoFactorEnabled) ...[
              Text('Two-factor is on.', style: TextStyle(color: scheme.primary)),
              const SizedBox(height: 8),
              TextField(
                controller: _twoFaCode,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Current code to turn off'),
              ),
              const SizedBox(height: 8),
              FilledButton(
                onPressed: _busy2fa ? null : _disable2fa,
                style: FilledButton.styleFrom(backgroundColor: scheme.error),
                child: const Text('Turn off'),
              ),
            ] else if (_twoFaSetup != null) ...[
              const Text('1. Add this key to your authenticator app:'),
              const SizedBox(height: 6),
              SelectableText(
                _twoFaSetup!['secret'] ?? '',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 16, letterSpacing: 1.5),
              ),
              const SizedBox(height: 8),
              const Text('2. Enter the 6-digit code it shows:'),
              const SizedBox(height: 6),
              TextField(
                controller: _twoFaCode,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: '6-digit code'),
              ),
              const SizedBox(height: 8),
              FilledButton(
                onPressed: _busy2fa ? null : _enable2fa,
                child: Text(_busy2fa ? 'Verifying...' : 'Verify & turn on'),
              ),
            ] else
              FilledButton(
                onPressed: _busy2fa ? null : _startSetup2fa,
                child: const Text('Set up two-factor'),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _startSetup2fa() async {
    await _guard(() async {
      final setup = await _api.twoFactorSetup();
      setState(() => _twoFaSetup = setup);
    }, (b) => setState(() => _busy2fa = b),);
  }

  Future<void> _enable2fa() async {
    await _guard(() async {
      await _api.twoFactorEnable(_twoFaCode.text);
      _twoFaCode.clear();
      setState(() => _twoFaSetup = null);
      ref.invalidate(accountProfileProvider);
      _toast(NoticeLevel.success, '2FA_ON', 'Two-factor is now on.');
    }, (b) => setState(() => _busy2fa = b),);
  }

  Future<void> _disable2fa() async {
    await _guard(() async {
      await _api.twoFactorDisable(_twoFaCode.text);
      _twoFaCode.clear();
      ref.invalidate(accountProfileProvider);
      _toast(NoticeLevel.info, '2FA_OFF', 'Two-factor has been turned off.');
    }, (b) => setState(() => _busy2fa = b),);
  }

  // ---- session ------------------------------------------------------------
  Widget _sessionCard(ColorScheme scheme) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Session', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
            const SizedBox(height: 6),
            Row(
              children: [
                Expanded(
                  child: Text('Signed in as ${_p.email}',
                      style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),),
                ),
                if (_p.admin)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    decoration: BoxDecoration(
                      color: scheme.primaryContainer,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text('ADMIN',
                        style: TextStyle(color: scheme.onPrimaryContainer, fontSize: 11, fontWeight: FontWeight.w700),),
                  ),
              ],
            ),
            const SizedBox(height: 10),
            OutlinedButton.icon(
              onPressed: () => ref.read(authControllerProvider.notifier).logout(),
              icon: const Icon(Icons.logout, size: 18),
              label: const Text('Sign out'),
            ),
          ],
        ),
      ),
    );
  }
}
