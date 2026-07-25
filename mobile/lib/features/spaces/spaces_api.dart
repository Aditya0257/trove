/// ============================================================================
///  spaces_api - create spaces, manage members, invitations and join links
/// ============================================================================
///  Purpose:  the shared-space collaboration calls used by the home invitations
///            banner and the space management screen.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/models/membership.dart';
import '../../core/providers.dart';

class SpacesApi {
  SpacesApi(this._ref);
  final Ref _ref;
  ApiClient get _api => _ref.read(apiClientProvider);

  Future<void> create(String name) async {
    await _api.post('/api/spaces', body: {'name': name.trim()});
  }

  Future<List<Invitation>> invitations() async {
    final data = await _api.get('/api/spaces/invitations') as List<dynamic>;
    return data.map((e) => Invitation.fromJson((e as Map).cast<String, dynamic>())).toList();
  }

  Future<void> accept(String spaceId) async {
    await _api.post('/api/spaces/$spaceId/invitations/accept');
  }

  Future<void> decline(String spaceId) async {
    await _api.post('/api/spaces/$spaceId/invitations/decline');
  }

  Future<List<Member>> members(String spaceId) async {
    final data = await _api.get('/api/spaces/$spaceId/members') as List<dynamic>;
    return data.map((e) => Member.fromJson((e as Map).cast<String, dynamic>())).toList();
  }

  Future<void> addMember(String spaceId, String email, String role) async {
    await _api.post('/api/spaces/$spaceId/members', body: {'email': email.trim(), 'role': role});
  }

  Future<void> approveMember(String spaceId, String userId) async {
    await _api.post('/api/spaces/$spaceId/members/$userId/approve');
  }

  Future<String?> joinLink(String spaceId) async {
    final data = await _api.get('/api/spaces/$spaceId/join-link') as Map<String, dynamic>;
    return data['url'] as String?;
  }

  Future<String> ingestAddress(String spaceId) async {
    final data = await _api.get('/api/spaces/$spaceId/ingest-address') as Map<String, dynamic>;
    return data['address'] as String;
  }

  Future<String> rotateIngestAddress(String spaceId) async {
    final data =
        await _api.post('/api/spaces/$spaceId/ingest-address/rotate') as Map<String, dynamic>;
    return data['address'] as String;
  }
}

final spacesApiProvider = Provider<SpacesApi>((ref) => SpacesApi(ref));

/// Pending invitations for the signed-in user.
final invitationsProvider = FutureProvider.autoDispose<List<Invitation>>(
    (ref) => ref.watch(spacesApiProvider).invitations(),);

/// Members of a space (owner-only on the backend).
final membersProvider = FutureProvider.autoDispose.family<List<Member>, String>(
    (ref, spaceId) => ref.watch(spacesApiProvider).members(spaceId),);
