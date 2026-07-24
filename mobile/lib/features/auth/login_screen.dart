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
    if (outcome == AuthOutcome.needCode && mounted) {
      // 2FA is on: reveal the code field and let the user enter it, then resubmit.
      setState(() => _needCode = true);
    }
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
                    obscureText: true,
                    textInputAction:
                        _needCode ? TextInputAction.next : TextInputAction.done,
                    onSubmitted: (_) => busy ? null : _submit(),
                    decoration: const InputDecoration(labelText: 'Password'),
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
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
