/*
 * ============================================================================
 *  SpaceAuthorization — the single gate for "can this user do this in this space?"
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Centralizes membership + role checks so every feature enforces access the same
 *  way: read requires membership; write requires owner/member; admin requires owner.
 *
 *  Business use case
 *  -----------------
 *  This is where multi-user access control lives. A document is only ever touched
 *  on behalf of a user who is a member of its space with a sufficient role —
 *  otherwise 403. Getting this in one place prevents accidental leaks.
 *
 *  Solution architecture
 *  ---------------------
 *  Backed by space_member. Called by DocumentService (and SpaceService) before any
 *  space-scoped operation. Throws ForbiddenException (→ 403) on failure.
 *
 *  Reasoning & logic
 *  -----------------
 *  Roles: owner (admin + write + read), member (write + read), viewer (read only).
 * ============================================================================
 */
package com.trove.space;

import com.trove.common.error.ForbiddenException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SpaceAuthorization {

    private final SpaceMemberRepository memberRepository;

    public SpaceAuthorization(SpaceMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /** Returns the user's role in the space, or throws 403 unless they are an ACTIVE
     *  member. A pending (unaccepted) or declined invite grants no access. */
    public String requireMembership(UUID spaceId, UUID userId) {
        return memberRepository.findBySpaceIdAndUserId(spaceId, userId)
                .filter(m -> MembershipStatus.ACTIVE.equals(m.getStatus()))
                .map(SpaceMember::getRole)
                .orElseThrow(() -> new ForbiddenException("Not a member of this space"));
    }

    /** Any member may read. */
    public void requireCanRead(UUID spaceId, UUID userId) {
        requireMembership(spaceId, userId);
    }

    /** Only owner/member may modify (viewers are read-only). */
    public void requireCanWrite(UUID spaceId, UUID userId) {
        String role = requireMembership(spaceId, userId);
        if (!SpaceRole.canWrite(role)) {
            throw new ForbiddenException("Your role '" + role + "' cannot modify documents in this space");
        }
    }

    /** Only the owner may administer the space (add/remove members). */
    public void requireOwner(UUID spaceId, UUID userId) {
        String role = requireMembership(spaceId, userId);
        if (!SpaceRole.OWNER.equals(role)) {
            throw new ForbiddenException("Only the space owner can perform this action");
        }
    }
}
