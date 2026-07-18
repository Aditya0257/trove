/*
 * ============================================================================
 *  SpaceController — REST surface for spaces and membership
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Endpoints to create shared spaces, list the caller's spaces, view a space's
 *  members, and add members with roles.
 *
 *  Business use case
 *  -----------------
 *  Lets a user set up a household/friends/project space and invite others at the
 *  right role — the multi-user half of Trove.
 *
 *  Solution architecture
 *  ---------------------
 *  Base path /api/spaces (authenticated). The acting user comes from the JWT via
 *  CurrentUser; SpaceService enforces owner-only actions. Request/response DTOs are
 *  nested records to keep the space API in one place.
 * ============================================================================
 */
package com.trove.space;

import com.trove.common.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/spaces")
public class SpaceController {

    private final SpaceService spaceService;
    private final CurrentUser currentUser;

    public SpaceController(SpaceService spaceService, CurrentUser currentUser) {
        this.spaceService = spaceService;
        this.currentUser = currentUser;
    }

    /** Create a shared space (caller becomes owner). */
    @PostMapping
    public ResponseEntity<SpaceResponse> create(@RequestBody CreateSpaceRequest req) {
        Space s = spaceService.createSharedSpace(currentUser.requireUserId(), req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(SpaceResponse.of(s));
    }

    /** List the spaces the caller belongs to. */
    @GetMapping
    public List<SpaceResponse> mySpaces() {
        return spaceService.listForUser(currentUser.requireUserId()).stream()
                .map(SpaceResponse::of).toList();
    }

    /** List members of a space (any member). */
    @GetMapping("/{id}/members")
    public List<MemberResponse> members(@PathVariable UUID id) {
        return spaceService.listMembers(id, currentUser.requireUserId()).stream()
                .map(m -> new MemberResponse(m.getUserId(), m.getRole(), m.getJoinedAt()))
                .toList();
    }

    /** Add or re-role a member by email (owner only). */
    @PostMapping("/{id}/members")
    public MemberResponse addMember(@PathVariable UUID id, @RequestBody AddMemberRequest req) {
        SpaceMember m = spaceService.addMember(id, currentUser.requireUserId(), req.email(), req.role());
        return new MemberResponse(m.getUserId(), m.getRole(), m.getJoinedAt());
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record CreateSpaceRequest(@NotBlank String name) {
    }

    public record AddMemberRequest(@NotBlank String email, @NotBlank String role) {
    }

    public record SpaceResponse(UUID id, String name, String kind, UUID createdBy, Instant createdAt) {
        static SpaceResponse of(Space s) {
            return new SpaceResponse(s.getId(), s.getName(), s.getKind(), s.getCreatedBy(), s.getCreatedAt());
        }
    }

    public record MemberResponse(UUID userId, String role, Instant joinedAt) {
    }
}
