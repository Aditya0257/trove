/// ============================================================================
///  LoginScreen — sign in / register
/// ============================================================================
///  Purpose:  the front door. Toggles between login and register; submits via
///            AuthController; the router redirects to /home on success. Failures
///            surface as tailored notices (from the API envelope) automatically.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/auth/auth_controller.dart';
import '../../core/config.dart';
import '../../core/notice/notice.dart';
import '../../core/notice/notice_center.dart';
import '../../ui/widgets/help_card.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _name = TextEditingController();
  final _code = TextEditingController();
  bool _register = false;
  bool _needCode = false; // revealed once the backend asks for a 2FA code
  bool _needVerify = false; // shown once the backend says the email needs verifying
  bool _pending = false; // shown after verify when the account awaits admin approval
  bool _showPassword = false; // toggles the password field's own reveal eye
  String _verifyEmail = ''; // the address being verified

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    _name.dispose();
    _code.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final ctrl = ref.read(authControllerProvider.notifier);
    final outcome = _register
        ? await ctrl.register(_email.text, _password.text, _name.text)
        : await ctrl.login(_email.text, _password.text,
            code: _needCode ? _code.text : null,);
    if (!mounted) return;
    if (outcome == AuthOutcome.needCode) {
      // 2FA is on: reveal the code field and let the user enter it, then resubmit.
      setState(() => _needCode = true);
    } else if (outcome == AuthOutcome.needVerify) {
      // Email not verified: switch to the code-entry step.
      setState(() {
        _needVerify = true;
        _verifyEmail = _email.text.trim();
        _code.clear();
      });
    }
  }

  Future<void> _verify() async {
    final outcome = await ref.read(authControllerProvider.notifier).verifyEmail(_verifyEmail, _code.text);
    // success -> the router redirects on the new session; pending -> show the calm
    // "awaiting admin approval" panel (the controller also raises a notice).
    if (outcome == AuthOutcome.pending && mounted) {
      setState(() {
        _needVerify = false;
        _register = false;
        _pending = true;
        _password.clear();
        _code.clear();
      });
    }
  }

  Future<void> _resendVerify() async {
    await ref.read(authControllerProvider.notifier).resendVerification(_verifyEmail);
  }

  Future<void> _forgot() async {
    final email = _email.text.trim();
    if (email.isEmpty) {
      NoticeCenter.instance.show(Notice.local(
        level: NoticeLevel.warning,
        code: 'RESET_NEEDS_EMAIL',
        userMessage: 'Enter your email above, then tap Forgot password.',
      ),);
      return;
    }
    await ref.read(authControllerProvider.notifier).forgotPassword(email);
  }

  @override
  Widget build(BuildContext context) {
    final busy = ref.watch(authControllerProvider).isLoading;
    final scheme = Theme.of(context).colorScheme;

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(AppConfig.appName,
                      style: Theme.of(context)
                          .textTheme
                          .headlineMedium
                          ?.copyWith(fontWeight: FontWeight.w800),),
                  const SizedBox(height: 4),
                  Text('Your private document vault.',
                      style: TextStyle(color: scheme.onSurfaceVariant),),
                  const SizedBox(height: 32),
                  if (_pending) ...[
                    Icon(Icons.mark_email_read_outlined, size: 44, color: scheme.primary),
                    const SizedBox(height: 12),
                    Text(
                      'Almost there',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Your email is verified and your request has gone to the admin. '
                      "You'll get an email the moment your account is approved, and then "
                      'you can sign in here. Nothing more to do for now.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13, height: 1.5),
                    ),
                    const SizedBox(height: 24),
                    FilledButton(
                      onPressed: () => setState(() => _pending = false),
                      child: const Text('Back to sign in'),
                    ),
                  ] else if (_needVerify) ...[
                    Text(
                      'Verify your email  (step 2 of 3)',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'We emailed a 6-digit code to $_verifyEmail. Enter it below to confirm the '
                      'address is really yours. After this an admin approves your account, and '
                      'then you can sign in. (We verify email first because Trove sends password '
                      'resets and reminders there, so it must be real and reachable.)',
                      style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13, height: 1.5),
                    ),
                    const SizedBox(height: 16),
                    TextField(
                      controller: _code,
                      keyboardType: TextInputType.number,
                      textInputAction: TextInputAction.done,
                      autofocus: true,
                      maxLength: 6,
                      onSubmitted: (_) => busy ? null : _verify(),
                      decoration: const InputDecoration(labelText: 'Email code', counterText: ''),
                    ),
                    const SizedBox(height: 20),
                    FilledButton(
                      onPressed: busy ? null : _verify,
                      child: busy
                          ? const SizedBox(
                              height: 20,
                              width: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Text('Verify email'),
                    ),
                    const SizedBox(height: 8),
                    TextButton(
                      onPressed: busy ? null : _resendVerify,
                      child: const Text('Resend code'),
                    ),
                    TextButton(
                      onPressed: busy
                          ? null
                          : () => setState(() {
                                _needVerify = false;
                                _code.clear();
                              }),
                      child: const Text('Back'),
                    ),
                  ] else ...[
                    if (_register)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: TextField(
                          controller: _name,
                          textInputAction: TextInputAction.next,
                          decoration: const InputDecoration(labelText: 'Name (optional)'),
                        ),
                      ),
                    TextField(
                      controller: _email,
                      keyboardType: TextInputType.emailAddress,
                      textInputAction: TextInputAction.next,
                      autofillHints: const [AutofillHints.email],
                      decoration: const InputDecoration(labelText: 'Email'),
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      controller: _password,
                      obscureText: !_showPassword,
                      textInputAction:
                          _needCode ? TextInputAction.next : TextInputAction.done,
                      onSubmitted: (_) => busy ? null : _submit(),
                      decoration: InputDecoration(
                        labelText: 'Password',
                        suffixIcon: IconButton(
                          icon: Icon(
                            _showPassword
                                ? Icons.visibility_off_outlined
                                : Icons.visibility_outlined,
                          ),
                          tooltip: _showPassword ? 'Hide password' : 'Show password',
                          onPressed: () => setState(() => _showPassword = !_showPassword),
                        ),
                      ),
                    ),
                    if (_needCode) ...[
                      const SizedBox(height: 12),
                      TextField(
                        controller: _code,
                        keyboardType: TextInputType.number,
                        textInputAction: TextInputAction.done,
                        autofocus: true,
                        maxLength: 6,
                        onSubmitted: (_) => busy ? null : _submit(),
                        decoration: const InputDecoration(
                          labelText: 'Authenticator code',
                          helperText: 'Enter the 6-digit code from your authenticator app',
                          counterText: '',
                        ),
                      ),
                    ],
                    const SizedBox(height: 20),
                    FilledButton(
                      onPressed: busy ? null : _submit,
                      child: busy
                          ? const SizedBox(
                              height: 20,
                              width: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),)
                          : Text(_needCode
                              ? 'Verify'
                              : (_register ? 'Create account' : 'Sign in'),),
                    ),
                    const SizedBox(height: 8),
                    TextButton(
                      onPressed: busy
                          ? null
                          : () => setState(() {
                                _register = !_register;
                                _needCode = false;
                                _code.clear();
                              }),
                      child: Text(_register
                          ? 'I already have an account'
                          : 'New here? Create an account',),
                    ),
                    if (!_register && !_needCode)
                      TextButton(
                        onPressed: busy ? null : _forgot,
                        child: const Text('Forgot password?'),
                      ),
                    if (_register && !_needCode) ...[
                      const SizedBox(height: 12),
                      const HelpCard(
                        title: 'How signing up works',
                        user: 'Getting in takes a few steps. First you register here with your '
                            'email and a password. Next we email you a 6-digit code - type it in '
                            "to prove the address is yours. After that, an admin reviews and "
                            'approves your account (Trove is kept small and invite-only). You will '
                            'get an email the moment you are approved, and then you can sign in.',
                        dev: null,
                      ),
                    ],
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
