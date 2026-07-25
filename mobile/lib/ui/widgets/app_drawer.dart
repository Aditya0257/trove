/// ============================================================================
///  AppDrawer - the left navigation drawer (one tap to anywhere)
/// ============================================================================
///
///  Purpose
///  -------
///  Central navigation so every destination is reachable from one place: the
///  space's sections (Mail, Search, Spend, Reminders, Backups, Trash, Ask), plus
///  global items (all spaces, Account, Admin, Developer), the light/dark toggle, and
///  sign out. Shown on the home and document-list hubs.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/auth/auth_controller.dart';
import '../../core/models/space.dart';
import '../../core/providers.dart';
import '../../core/theme_controller.dart';

class AppDrawer extends ConsumerWidget {
  const AppDrawer({super.key, this.space});

  /// The current space (null on screens without one); space-scoped items hide when null.
  final Space? space;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final user = ref.watch(authStoreProvider).user;
    final mode = ref.watch(themeModeProvider);
    final s = space;

    void go(String route) {
      Navigator.pop(context); // close the drawer
      context.push(route, extra: s?.id);
    }

    return Drawer(
      child: ListView(
        padding: EdgeInsets.zero,
        children: [
          DrawerHeader(
            decoration: BoxDecoration(color: scheme.primaryContainer),
            margin: EdgeInsets.zero,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                Text('Trove',
                    style: TextStyle(
                        color: scheme.onPrimaryContainer,
                        fontSize: 22,
                        fontWeight: FontWeight.w800,),),
                if (s != null)
                  Text(s.name,
                      style: TextStyle(color: scheme.onPrimaryContainer, fontSize: 13),),
              ],
            ),
          ),
          if (s != null) ...[
            _item(context, Icons.auto_awesome_outlined, 'Ask your vault', () => go('/chat')),
            _item(context, Icons.mail_outline, 'Mail', () => go('/mail')),
            _item(context, Icons.search, 'Search', () => go('/search')),
            _item(context, Icons.bar_chart_outlined, 'Spend', () => go('/spend')),
            _item(context, Icons.notifications_none, 'Reminders', () => go('/reminders')),
            _item(context, Icons.cloud_done_outlined, 'Backups & data health', () => go('/backups')),
            _item(context, Icons.delete_outline, 'Trash', () => go('/trash')),
            if (!s.isPersonal)
              _item(context, Icons.manage_accounts_outlined, 'Manage space',
                  () { Navigator.pop(context); context.push('/space-manage', extra: s); },),
            const Divider(),
          ],
          _item(context, Icons.folder_outlined, 'All spaces',
              () { Navigator.pop(context); context.go('/home'); },),
          _item(context, Icons.account_circle_outlined, 'Your profile',
              () { Navigator.pop(context); context.push('/account'); },),
          if (user?.admin ?? false)
            _item(context, Icons.admin_panel_settings_outlined, 'Admin',
                () { Navigator.pop(context); context.push('/admin'); },),
          const Divider(),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: Row(
              children: [
                Icon(Icons.brightness_6_outlined, size: 20, color: scheme.onSurfaceVariant),
                const SizedBox(width: 12),
                Expanded(
                  child: SegmentedButton<ThemeMode>(
                    showSelectedIcon: false,
                    style: const ButtonStyle(visualDensity: VisualDensity.compact),
                    segments: const [
                      ButtonSegment(value: ThemeMode.system, label: Text('Auto')),
                      ButtonSegment(value: ThemeMode.light, label: Text('Light')),
                      ButtonSegment(value: ThemeMode.dark, label: Text('Dark')),
                    ],
                    selected: {mode},
                    onSelectionChanged: (set) =>
                        ref.read(themeModeProvider.notifier).set(set.first),
                  ),
                ),
              ],
            ),
          ),
          _item(context, Icons.logout, 'Sign out', () {
            Navigator.pop(context);
            ref.read(authControllerProvider.notifier).logout();
          }),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  Widget _item(BuildContext context, IconData icon, String label, VoidCallback onTap) =>
      ListTile(
        leading: Icon(icon),
        title: Text(label),
        onTap: onTap,
      );
}
