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

import '../../core/models/space.dart';
import '../../core/providers.dart';
import '../../ui/widgets/app_drawer.dart';
import '../../ui/widgets/dev_drawer.dart';
import '../spaces/spaces_api.dart';

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
      drawer: AppDrawer(space: captureSpace),
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
          IconButton(
            tooltip: 'New shared space',
            icon: const Icon(Icons.create_new_folder_outlined),
            onPressed: () => _createSpace(context, ref),
          ),
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
          if (user?.admin ?? false)
            IconButton(
              tooltip: 'Admin',
              icon: const Icon(Icons.admin_panel_settings_outlined),
              onPressed: () => context.push('/admin'),
            ),
          IconButton(
            tooltip: 'Your profile',
            icon: const Icon(Icons.account_circle_outlined),
            onPressed: () => context.push('/account'),
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
            const SizedBox(height: 12),
            _invitations(context, ref, scheme),
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

  /// The pending-invitations banner (empty when there are none).
  Widget _invitations(BuildContext context, WidgetRef ref, ColorScheme scheme) {
    final invites = ref.watch(invitationsProvider);
    return invites.maybeWhen(
      data: (list) => list.isEmpty
          ? const SizedBox.shrink()
          : Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Invitations', style: TextStyle(color: scheme.onSurfaceVariant)),
                const SizedBox(height: 8),
                for (final inv in list)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 12, 8, 8),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(inv.spaceName, style: const TextStyle(fontWeight: FontWeight.w600)),
                          Text(
                            inv.invitedByName != null
                                ? '${inv.role} - invited by ${inv.invitedByName}'
                                : inv.role,
                            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
                          ),
                          const SizedBox(height: 4),
                          Row(
                            mainAxisAlignment: MainAxisAlignment.end,
                            children: [
                              TextButton(
                                onPressed: () => _respondInvite(ref, inv.spaceId, false),
                                child: const Text('Decline'),
                              ),
                              FilledButton(
                                onPressed: () => _respondInvite(ref, inv.spaceId, true),
                                child: const Text('Accept'),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                const SizedBox(height: 16),
              ],
            ),
      orElse: () => const SizedBox.shrink(),
    );
  }

  Future<void> _respondInvite(WidgetRef ref, String spaceId, bool accept) async {
    final api = ref.read(spacesApiProvider);
    if (accept) {
      await api.accept(spaceId);
    } else {
      await api.decline(spaceId);
    }
    ref.invalidate(invitationsProvider);
    ref.invalidate(spacesProvider);
  }

  /// Prompt for a name and create a shared space.
  Future<void> _createSpace(BuildContext context, WidgetRef ref) async {
    final ctrl = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('New shared space'),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'Name', hintText: 'e.g. Household'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, ctrl.text.trim()), child: const Text('Create')),
        ],
      ),
    );
    ctrl.dispose();
    if (name == null || name.isEmpty) return;
    await ref.read(spacesApiProvider).create(name);
    ref.invalidate(spacesProvider);
  }
}
