package com.trove.service;

import com.trove.entity.Space;
import com.trove.entity.SpaceMember;
import java.util.List;
import java.util.UUID;

/** Service contract for SpaceService. */
public interface SpaceService {
    Space createPersonalSpace(UUID userId, String displayName);
    Space createSharedSpace(UUID userId, String name);
    List<Space> listForUser(UUID userId);
    UUID ownerId(UUID spaceId);
    UUID personalSpaceId(UUID userId);
    List<SpaceMember> listMembers(UUID spaceId, UUID actingUserId);
    SpaceMember addMember(UUID spaceId, UUID actingUserId, String email, String role);
    SpaceMember respondToInvite(UUID spaceId, UUID userId, boolean accept);
    List<SpaceMember> pendingInvitations(UUID userId);
    String joinToken(UUID spaceId, UUID actingUserId, boolean rotate);
    void revokeJoinToken(UUID spaceId, UUID actingUserId);
    Space requestJoin(String token, UUID userId);
    SpaceMember approveMember(UUID spaceId, UUID actingUserId, UUID targetUserId);
    void removeMember(UUID spaceId, UUID actingUserId, UUID targetUserId);
    Space getSpace(UUID spaceId);
    Space updateSpace(UUID spaceId, UUID actingUserId, String name, String description);
    void deleteSpace(UUID spaceId, UUID actingUserId);
}
