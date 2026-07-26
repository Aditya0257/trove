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

import '../../core/default_space_controller.dart';
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
    final defaultId = ref.watch(defaultSpaceProvider);
    final scheme = Theme.of(context).colorScheme;

    // The active space (drawer + quick-add target) is the user's chosen default if it
    // still exists, otherwise their personal space. Never null once spaces have loaded.
    final spaceList = spaces.asData?.value ?? const <Space>[];
    final captureSpace = spaceList.isEmpty
        ? null
        : spaceList.firstWhere(
            (s) => s.id == defaultId,
            orElse: () =>
                spaceList.firstWhere((s) => s.isPersonal, orElse: () => spaceList.first),
          );

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
          Builder(
            builder: (context) => IconButton(
              tooltip: 'Developer',
              icon: const Icon(Icons.terminal),
              onPressed: () => Scaffold.of(context).openEndDrawer(),
            ),
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
            const SizedBox(height: 4),
            Text(
              'Tap a space to open it. The pinned space is your default - the app opens '
              'there and the menu (Mail, Spend, Reminders...) acts on it. Tap the pin on '
              'another space to make that one your default instead.',
              style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
            ),
            const SizedBox(height: 12),
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
                        subtitle: Text(
                          s.id == captureSpace?.id ? '${s.kind} - opens by default' : s.kind,
                        ),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            IconButton(
                              // A pin (not a star - a star reads as "favourite"): the pinned
                              // space is where the app opens. The active space shows filled,
                              // so a first-time user sees their personal space already pinned.
                              tooltip: s.id == captureSpace?.id
                                  ? 'Your default space - the app opens here'
                                  : 'Make this your default space',
                              icon: Icon(
                                s.id == captureSpace?.id
                                    ? Icons.push_pin
                                    : Icons.push_pin_outlined,
                                color: s.id == captureSpace?.id
                                    ? scheme.primary
                                    : scheme.onSurfaceVariant,
                              ),
                              onPressed: () => ref
                                  .read(defaultSpaceProvider.notifier)
                                  .set(s.id == defaultId ? null : s.id),
                            ),
                            const Icon(Icons.chevron_right),
                          ],
                        ),
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

  /// Prompt for a name and create a shared space. The dialog owns its own text
  /// controller (see [_NewSpaceDialog]) so cancelling or backing out never disposes
  /// a controller a still-animating field is reading (that was the crash-screen bug).
  Future<void> _createSpace(BuildContext context, WidgetRef ref) async {
    final name = await showDialog<String>(
      context: context,
      builder: (_) => const _NewSpaceDialog(),
    );
    if (name == null || name.isEmpty) return;
    await ref.read(spacesApiProvider).create(name);
    ref.invalidate(spacesProvider);
  }
}

/// The "new shared space" dialog. A StatefulWidget so its TextEditingController is
/// tied to the dialog's own lifecycle and disposed only after the route is gone.
class _NewSpaceDialog extends StatefulWidget {
  const _NewSpaceDialog();

  @override
  State<_NewSpaceDialog> createState() => _NewSpaceDialogState();
}

class _NewSpaceDialogState extends State<_NewSpaceDialog> {
  final _ctrl = TextEditingController();

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('New shared space'),
      content: TextField(
        controller: _ctrl,
        autofocus: true,
        decoration: const InputDecoration(labelText: 'Name', hintText: 'e.g. Household'),
        onSubmitted: (v) => Navigator.pop(context, v.trim()),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: () => Navigator.pop(context, _ctrl.text.trim()),
          child: const Text('Create'),
        ),
      ],
    );
  }
}
