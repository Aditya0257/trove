/// ============================================================================
///  main — bootstrap, routing, and the global notice overlay
/// ============================================================================
///
///  Purpose
///  -------
///  Restores any saved session before the first frame, then runs the app: a
///  go_router with an auth redirect, themed light/dark, wrapped in a NoticeHost that
///  shows two-channel toasts anywhere in the app (D23).
///
///  Design
///  ------
///  AuthStore is restored inside a ProviderContainer so the initial route is correct
///  on cold start. NoticeHost listens to the NoticeCenter singleton and overlays the
///  latest notice, auto-dismissing by level.
/// ============================================================================
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'core/models/document.dart';
import 'core/models/space.dart';
import 'core/notice/notice.dart';
import 'core/notice/notice_center.dart';
import 'core/providers.dart';
import 'features/auth/login_screen.dart';
import 'features/documents/capture_screen.dart';
import 'features/documents/confirm_screen.dart';
import 'features/documents/detail_screen.dart';
import 'features/documents/list_screen.dart';
import 'features/home/home_shell.dart';
import 'features/search/search_screen.dart';
import 'ui/theme.dart';
import 'ui/widgets/notice_toast.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final container = ProviderContainer();
  await container.read(authStoreProvider).restore();
  runApp(UncontrolledProviderScope(container: container, child: const TroveApp()));
}

class TroveApp extends ConsumerStatefulWidget {
  const TroveApp({super.key});

  @override
  ConsumerState<TroveApp> createState() => _TroveAppState();
}

class _TroveAppState extends ConsumerState<TroveApp> {
  late final GoRouter _router;

  @override
  void initState() {
    super.initState();
    final auth = ref.read(authStoreProvider);
    _router = GoRouter(
      refreshListenable: auth,
      initialLocation: '/home',
      redirect: (context, state) {
        final loggedIn = auth.isAuthenticated;
        final loggingIn = state.matchedLocation == '/login';
        if (!loggedIn) return loggingIn ? null : '/login';
        if (loggingIn) return '/home';
        return null;
      },
      routes: [
        GoRoute(path: '/login', builder: (_, __) => const LoginScreen()),
        GoRoute(path: '/home', builder: (_, __) => const HomeShell()),
        GoRoute(
          path: '/capture',
          builder: (_, state) => CaptureScreen(spaceId: state.extra! as String),
        ),
        GoRoute(
          path: '/confirm',
          builder: (_, state) => ConfirmScreen(doc: state.extra! as TroveDocument),
        ),
        GoRoute(
          path: '/documents',
          builder: (_, state) => DocumentListScreen(space: state.extra! as Space),
        ),
        GoRoute(
          path: '/document',
          builder: (_, state) => DocumentDetailScreen(initial: state.extra! as TroveDocument),
        ),
        GoRoute(
          path: '/search',
          builder: (_, state) => SearchScreen(spaceId: state.extra! as String),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'Trove',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light(),
      darkTheme: AppTheme.dark(),
      routerConfig: _router,
      builder: (context, child) => NoticeHost(child: child ?? const SizedBox.shrink()),
    );
  }
}

/// Overlays the latest Notice as a top toast, above all routes.
class NoticeHost extends StatefulWidget {
  const NoticeHost({super.key, required this.child});
  final Widget child;

  @override
  State<NoticeHost> createState() => _NoticeHostState();
}

class _NoticeHostState extends State<NoticeHost> {
  Notice? _current;
  int _seen = -1;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    NoticeCenter.instance.addListener(_onNotice);
  }

  @override
  void dispose() {
    NoticeCenter.instance.removeListener(_onNotice);
    _timer?.cancel();
    super.dispose();
  }

  void _onNotice() {
    final center = NoticeCenter.instance;
    if (center.token == _seen) return;
    _seen = center.token;
    setState(() => _current = center.latest);
    _timer?.cancel();
    final ms = switch (_current!.level) {
      NoticeLevel.error => 8000,
      NoticeLevel.warning => 7000,
      _ => 4000,
    };
    _timer = Timer(Duration(milliseconds: ms), _dismiss);
  }

  void _dismiss() {
    if (mounted) setState(() => _current = null);
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        widget.child,
        if (_current != null)
          Positioned(
            top: MediaQuery.of(context).padding.top + 8,
            left: 12,
            right: 12,
            child: SafeArea(
              bottom: false,
              child: NoticeToast(notice: _current!, onDismiss: _dismiss),
            ),
          ),
      ],
    );
  }
}
