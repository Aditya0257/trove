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
import 'core/notifications/notification_service.dart';
import 'core/providers.dart';
import 'core/theme_controller.dart';
import 'features/account/account_screen.dart';
import 'features/admin/admin_screen.dart';
import 'features/auth/login_screen.dart';
import 'features/backups/backups_screen.dart';
import 'features/chat/chat_screen.dart';
import 'features/mail/mail_compose_screen.dart';
import 'features/mail/mail_detail_screen.dart';
import 'features/mail/mail_list_screen.dart';
import 'features/documents/capture_screen.dart';
import 'features/documents/confirm_screen.dart';
import 'features/documents/detail_screen.dart';
import 'features/documents/list_screen.dart';
import 'features/documents/trash_screen.dart';
import 'features/home/home_shell.dart';
import 'features/reminders/reminders_screen.dart';
import 'features/search/search_screen.dart';
import 'features/spend/spend_screen.dart';
import 'features/spaces/space_manage_screen.dart';
import 'ui/theme.dart';
import 'ui/widgets/notice_toast.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // A calm placeholder instead of the raw red error screen if any widget fails to build.
  // (Release builds strip framework asserts, so most of these never reach the user.)
  ErrorWidget.builder = (FlutterErrorDetails details) => Container(
        alignment: Alignment.center,
        color: const Color(0xFF12151C),
        padding: const EdgeInsets.all(28),
        child: const Text(
          'Something went wrong on this screen.\nGo back and try again.',
          textAlign: TextAlign.center,
          textDirection: TextDirection.ltr,
          style: TextStyle(color: Colors.white70, fontSize: 15, height: 1.5),
        ),
      );
  final container = ProviderContainer();
  await container.read(authStoreProvider).restore();
  await NotificationService.instance.init();
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
        GoRoute(
          path: '/reminders',
          builder: (_, state) => RemindersScreen(spaceId: state.extra! as String),
        ),
        GoRoute(
          path: '/spend',
          builder: (_, state) => SpendScreen(spaceId: state.extra! as String),
        ),
        GoRoute(
          path: '/space-manage',
          builder: (_, state) => SpaceManageScreen(space: state.extra! as Space),
        ),
        GoRoute(path: '/account', builder: (_, __) => const AccountScreen()),
        GoRoute(path: '/admin', builder: (_, __) => const AdminScreen()),
        GoRoute(
          path: '/chat',
          builder: (_, state) => ChatScreen(spaceId: state.extra! as String),
        ),
        GoRoute(
          path: '/mail',
          builder: (_, state) => MailListScreen(spaceId: state.extra! as String),
        ),
        GoRoute(
          path: '/mail-compose',
          builder: (_, state) => MailComposeScreen(spaceId: state.extra! as String),
        ),
        GoRoute(
          path: '/trash',
          builder: (_, state) => TrashScreen(spaceId: state.extra! as String),
        ),
        GoRoute(
          path: '/mail-thread',
          builder: (_, state) {
            final args = state.extra! as Map<String, dynamic>;
            return MailThreadScreen(
              spaceId: args['spaceId'] as String,
              bundleId: args['bundleId'] as String,
            );
          },
        ),
        GoRoute(
          path: '/backups',
          builder: (_, state) => BackupsScreen(spaceId: state.extra! as String),
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
      themeMode: ref.watch(themeModeProvider),
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
    final next = center.latest;
    // Never surface an empty notice - a blank toast is just visual noise.
    if (next == null || next.userMessage.trim().isEmpty) return;
    setState(() => _current = next);
    _timer?.cancel();
    final ms = switch (next.level) {
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
    final media = MediaQuery.of(context);
    // Width scales with the screen (about 85% of it) rather than a fixed number, and
    // is clamped so it stays a tidy card on both a small phone and a wide tablet.
    final toastMaxWidth = (media.size.width * 0.85).clamp(260.0, 460.0);
    return Stack(
      children: [
        widget.child,
        if (_current != null)
          Positioned(
            bottom: media.padding.bottom + 72,
            left: 12,
            right: 12,
            child: SafeArea(
              top: false,
              // Centered and width-capped so the toast reads as a compact card with
              // clear side margins, not a full-width bar.
              child: Center(
                child: ConstrainedBox(
                  constraints: BoxConstraints(maxWidth: toastMaxWidth),
                  child: NoticeToast(notice: _current!, onDismiss: _dismiss),
                ),
              ),
            ),
          ),
      ],
    );
  }
}
