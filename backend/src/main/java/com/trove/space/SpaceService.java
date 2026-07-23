/*
 * ============================================================================
 *  SpaceService — create/join spaces and manage membership
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Business logic for spaces: create a user's personal space, create shared spaces,
 *  list the spaces a user belongs to, and add members with roles.
 *
 *  Business use case
 *  -----------------
 *  Personal space = private vault. Shared spaces = a household/friends/project
 *  keeping common documents together. Membership + role decide who can do what.
 *
 *  Solution architecture
 *  ---------------------
 *  Called by UserService (personal space on registration) and SpaceController.
 *  Delegates permission checks to SpaceAuthorization. Resolves invitees by email
 *  via UserRepository.
 *
 *  Reasoning & logic
 *  -----------------
 *  Creating a space always makes the creator its owner (an owner membership), so a
 *  space is never ownerless. Adding a member is owner-only.
 * ============================================================================
 */
package com.trove.space;

import com.trove.auth.User;
import com.trove.auth.UserRepository;
import com.trove.common.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SpaceService {

    private static final Set<String> VALID_ROLES = Set.of(SpaceRole.OWNER, SpaceRole.MEMBER, SpaceRole.VIEWER);

    private final SpaceRepository spaceRepository;
    private final SpaceMemberRepository memberRepository;
    private final SpaceAuthorization authorization;
    private final UserRepository userRepository;

    public SpaceService(SpaceRepository spaceRepository,
                        SpaceMemberRepository memberRepository,
                        SpaceAuthorization authorization,
                        UserRepository userRepository) {
        this.spaceRepository = spaceRepository;
        this.memberRepository = memberRepository;
        this.authorization = authorization;
        this.userRepository = userRepository;
    }

    /** Creates a user's private personal space and makes them its owner. */
    @Transactional
    public Space createPersonalSpace(UUID userId, String displayName) {
        Space space = spaceRepository.save(new Space(displayName + "'s Space", "personal", userId));
        memberRepository.save(new SpaceMember(space.getId(), userId, SpaceRole.OWNER));
        return space;
    }

    /** Creates a shared space and makes the creator its owner. */
    @Transactional
    public Space createSharedSpace(UUID userId, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Space name is required");
        }
        Space space = spaceRepository.save(new Space(name.trim(), "shared", userId));
        memberRepository.save(new SpaceMember(space.getId(), userId, SpaceRole.OWNER));
        return space;
    }

    /** All spaces the user belongs to. */
    @Transactional(readOnly = true)
    public List<Space> listForUser(UUID userId) {
        return spaceRepository.findAllForUser(userId);
    }

    /** The owner of a space (used to attribute forwarded/ingested documents). */
    @Transactional(readOnly = true)
    public UUID ownerId(UUID spaceId) {
        return memberRepository.findBySpaceId(spaceId).stream()
                .filter(m -> SpaceRole.OWNER.equals(m.getRole()))
                .map(SpaceMember::getUserId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("No owner for space " + spaceId));
    }

    /** The user's personal space id (used as the default target when none is given). */
    @Transactional(readOnly = true)
    public UUID personalSpaceId(UUID userId) {
        return spaceRepository.findAllForUser(userId).stream()
                .filter(s -> "personal".equals(s.getKind()))
                .map(Space::getId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("No personal space for user " + userId));
    }

    /** Members of a space (any member may view the roster). */
    @Transactional(readOnly = true)
    public List<SpaceMember> listMembers(UUID spaceId, UUID actingUserId) {
        authorization.requireMembership(spaceId, actingUserId);
        return memberRepository.findBySpaceId(spaceId);
    }

    /**
     * Invites a member by email (owner-only). The membership is created PENDING — the
     * invited user must accept before they can use the space. Re-inviting someone who
     * previously declined resets them to pending; an already-active member is simply
     * re-roled.
     */
    @Transactional
    public SpaceMember addMember(UUID spaceId, UUID actingUserId, String email, String role) {
        authorization.requireOwner(spaceId, actingUserId);
        if (!VALID_ROLES.contains(role)) {
            throw new IllegalArgumentException("Invalid role '" + role + "'. Use owner|member|viewer.");
        }
        User target = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new NotFoundException("No user with email " + email));

        java.util.Optional<SpaceMember> existing = memberRepository.findBySpaceIdAndUserId(spaceId, target.getId());
        // Re-roling someone who's already an active member keeps them active; every other
        // case (brand new, or a prior pending/declined) is a fresh invite → pending.
        boolean alreadyActive = existing.isPresent()
                && MembershipStatus.ACTIVE.equals(existing.get().getStatus());
        SpaceMember member = existing.orElseGet(() -> new SpaceMember(spaceId, target.getId(), role));
        member.setRole(role);
        if (!alreadyActive) {
            member.setStatus(MembershipStatus.PENDING);
            member.setInvitedBy(actingUserId);
        }
        return memberRepository.save(member);
    }

    /** The invited user accepts (→ active) or declines (→ declined) a pending invite. */
    @Transactional
    public SpaceMember respondToInvite(UUID spaceId, UUID userId, boolean accept) {
        SpaceMember member = memberRepository.findBySpaceIdAndUserId(spaceId, userId)
                .filter(m -> MembershipStatus.PENDING.equals(m.getStatus()))
                .orElseThrow(() -> new NotFoundException("No pending invitation for this space"));
        member.setStatus(accept ? MembershipStatus.ACTIVE : MembershipStatus.DECLINED);
        return memberRepository.save(member);
    }

    /** A user's outstanding (pending) invitations. */
    @Transactional(readOnly = true)
    public List<SpaceMember> pendingInvitations(UUID userId) {
        return memberRepository.findByUserIdAndStatus(userId, MembershipStatus.PENDING);
    }

    /**
     * Removes a member from a space (owner-only). Used both to remove an active member
     * and to dismiss a declined invite row. The owner can't remove themselves (a space
     * must always keep its owner).
     */
    @Transactional
    public void removeMember(UUID spaceId, UUID actingUserId, UUID targetUserId) {
        authorization.requireOwner(spaceId, actingUserId);
        if (actingUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("The owner cannot be removed from their own space");
        }
        memberRepository.findBySpaceIdAndUserId(spaceId, targetUserId)
                .ifPresent(memberRepository::delete);
    }

    /** Loads a space by id (for building invitation views). */
    @Transactional(readOnly = true)
    public Space getSpace(UUID spaceId) {
        return spaceRepository.findById(spaceId)
                .orElseThrow(() -> new NotFoundException("Space not found: " + spaceId));
    }
}
