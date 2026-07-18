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

    /** Adds (or re-roles) a member by email. Owner-only. */
    @Transactional
    public SpaceMember addMember(UUID spaceId, UUID actingUserId, String email, String role) {
        authorization.requireOwner(spaceId, actingUserId);
        if (!VALID_ROLES.contains(role)) {
            throw new IllegalArgumentException("Invalid role '" + role + "'. Use owner|member|viewer.");
        }
        User target = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new NotFoundException("No user with email " + email));

        SpaceMember member = memberRepository.findBySpaceIdAndUserId(spaceId, target.getId())
                .orElse(new SpaceMember(spaceId, target.getId(), role));
        member.setRole(role);
        return memberRepository.save(member);
    }
}
