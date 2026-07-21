/// ============================================================================
///  HomeShell — the signed-in landing (spaces + access to the Developer drawer)
/// ============================================================================
///
///  Purpose
///  -------
///  The first screen after sign-in. Loads the user's spaces (a real API call, so the
///  Notice System + Developer drawer are live from the start), greets the user, and
///  exposes the Developer drawer and sign-out. Document capture/list/search hang off
///  this shell in the next slices.
///
///  Design
///  ------
///  `spacesProvider` is a FutureProvider over ApiClient; the endDrawer is the
///  DeveloperDrawer. The Developer drawer is one tap away everywhere post-login.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/auth/auth_controller.dart';
import '../../core/models/space.dart';
import '../../core/providers.dart';
import '../../ui/widgets/dev_drawer.dart';

/// The current user's spaces (personal + shared).
final spacesProvider = FutureProvider.autoDispose<List<Space>>((ref) async {
  final api = ref.watch(apiClientProvider);
  final data = await api.get('/api/spaces') as List<dynamic>;
  return data
      .map((e) => Space.fromJson((e as Map).cast<String, dynamic>()))
      .toList();
});

class HomeShell extends ConsumerWidget {
  const HomeShell({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(authStoreProvider).user;
    final spaces = ref.watch(spacesProvider);
    final scheme = Theme.of(context).colorScheme;

    // Capture targets the chosen space, defaulting to the personal one.
    final spaceList = spaces.asData?.value ?? const <Space>[];
    final captureSpace = spaceList.isEmpty
        ? null
        : spaceList.firstWhere((s) => s.isPersonal, orElse: () => spaceList.first);

    return Scaffold(
      endDrawer: const DeveloperDrawer(),
      floatingActionButton: captureSpace == null
          ? null
          : FloatingActionButton.extended(
              onPressed: () => context.push('/capture', extra: captureSpace.id),
              icon: const Icon(Icons.add_a_photo_outlined),
              label: const Text('Add'),
            ),
      appBar: AppBar(
        title: const Text('Trove'),
        actions: [
          if (captureSpace != null)
            IconButton(
              tooltip: 'Reminders',
              icon: const Icon(Icons.notifications_none),
              onPressed: () => context.push('/reminders', extra: captureSpace.id),
            ),
          Builder(
            builder: (context) => IconButton(
              tooltip: 'Developer',
              icon: const Icon(Icons.terminal),
              onPressed: () => Scaffold.of(context).openEndDrawer(),
            ),
          ),
          IconButton(
            tooltip: 'Sign out',
            icon: const Icon(Icons.logout),
            onPressed: () => ref.read(authControllerProvider.notifier).logout(),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async => ref.refresh(spacesProvider.future),
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Text('Hello${user != null ? ', ${user.shortName}' : ''}.',
                style: Theme.of(context)
                    .textTheme
                    .headlineSmall
                    ?.copyWith(fontWeight: FontWeight.w700),),
            const SizedBox(height: 4),
            Text('Your spaces', style: TextStyle(color: scheme.onSurfaceVariant)),
            const SizedBox(height: 16),
            spaces.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(vertical: 40),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (e, _) => Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text('Couldn\'t load your spaces. Pull to retry.',
                      style: TextStyle(color: scheme.onSurfaceVariant),),
                ),
              ),
              data: (list) => Column(
                children: [
                  for (final s in list)
                    Card(
                      child: ListTile(
                        leading: Icon(s.isPersonal ? Icons.lock_outline : Icons.group_outlined),
                        title: Text(s.name),
                        subtitle: Text(s.kind),
                        trailing: const Icon(Icons.chevron_right),
                        onTap: () => context.push('/documents', extra: s),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
