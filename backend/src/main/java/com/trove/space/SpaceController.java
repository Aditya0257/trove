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

import com.trove.auth.User;
import com.trove.auth.UserRepository;
import com.trove.common.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/spaces")
public class SpaceController {

    private final SpaceService spaceService;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;

    public SpaceController(SpaceService spaceService, CurrentUser currentUser,
                           UserRepository userRepository) {
        this.spaceService = spaceService;
        this.currentUser = currentUser;
        this.userRepository = userRepository;
    }

    /** Create a shared space (caller becomes owner). */
    @PostMapping
    public ResponseEntity<SpaceResponse> create(@RequestBody CreateSpaceRequest req) {
        Space s = spaceService.createSharedSpace(currentUser.requireUserId(), req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(SpaceResponse.of(s));
    }

    /** Rename a space / set its description (owner only). */
    @PutMapping("/{id}")
    public SpaceResponse update(@PathVariable UUID id, @RequestBody UpdateSpaceRequest req) {
        return SpaceResponse.of(spaceService.updateSpace(id, currentUser.requireUserId(),
                req.name(), req.description()));
    }

    /** Delete a shared space and everything in it (owner only). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSpace(@PathVariable UUID id) {
        spaceService.deleteSpace(id, currentUser.requireUserId());
        return ResponseEntity.noContent().build();
    }

    /** List the spaces the caller belongs to. */
    @GetMapping
    public List<SpaceResponse> mySpaces() {
        return spaceService.listForUser(currentUser.requireUserId()).stream()
                .map(SpaceResponse::of).toList();
    }

    /** List members of a space (any member), enriched with each user's name + email. */
    @GetMapping("/{id}/members")
    public List<MemberResponse> members(@PathVariable UUID id) {
        List<SpaceMember> members = spaceService.listMembers(id, currentUser.requireUserId());
        // Batch-load the users so the UI can show a name + email instead of a raw UUID.
        Map<UUID, User> byId = userRepository
                .findAllById(members.stream().map(SpaceMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return members.stream()
                .map(m -> MemberResponse.of(m, byId.get(m.getUserId())))
                .toList();
    }

    /** Invite a member by email (owner only). Creates a PENDING invitation. */
    @PostMapping("/{id}/members")
    public MemberResponse addMember(@PathVariable UUID id, @RequestBody AddMemberRequest req) {
        SpaceMember m = spaceService.addMember(id, currentUser.requireUserId(), req.email(), req.role());
        return MemberResponse.of(m, userRepository.findById(m.getUserId()).orElse(null));
    }

    /** Remove a member, or dismiss a declined invite row (owner only). */
    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        spaceService.removeMember(id, currentUser.requireUserId(), userId);
        return ResponseEntity.noContent().build();
    }

    /** The caller's outstanding invitations (pending), with who invited them. */
    @GetMapping("/invitations")
    public List<InvitationResponse> invitations() {
        List<SpaceMember> pending = spaceService.pendingInvitations(currentUser.requireUserId());
        Map<UUID, User> inviters = userRepository
                .findAllById(pending.stream().map(SpaceMember::getInvitedBy).filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return pending.stream().map(m -> {
            Space s = spaceService.getSpace(m.getSpaceId());
            User inviter = m.getInvitedBy() != null ? inviters.get(m.getInvitedBy()) : null;
            return new InvitationResponse(s.getId(), s.getName(), s.getKind(), m.getRole(),
                    inviter != null ? inviter.getDisplayName() : null,
                    inviter != null ? inviter.getEmail() : null);
        }).toList();
    }

    /** Accept a pending invitation to this space. */
    @PostMapping("/{id}/invitations/accept")
    public MemberResponse acceptInvite(@PathVariable UUID id) {
        SpaceMember m = spaceService.respondToInvite(id, currentUser.requireUserId(), true);
        return MemberResponse.of(m, userRepository.findById(m.getUserId()).orElse(null));
    }

    /** Decline a pending invitation to this space. */
    @PostMapping("/{id}/invitations/decline")
    public MemberResponse declineInvite(@PathVariable UUID id) {
        SpaceMember m = spaceService.respondToInvite(id, currentUser.requireUserId(), false);
        return MemberResponse.of(m, userRepository.findById(m.getUserId()).orElse(null));
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record CreateSpaceRequest(@NotBlank String name) {
    }

    public record UpdateSpaceRequest(String name, String description) {
    }

    public record AddMemberRequest(@NotBlank String email, @NotBlank String role) {
    }

    public record SpaceResponse(UUID id, String name, String description, String kind,
                                UUID createdBy, Instant createdAt) {
        static SpaceResponse of(Space s) {
            return new SpaceResponse(s.getId(), s.getName(), s.getDescription(), s.getKind(),
                    s.getCreatedBy(), s.getCreatedAt());
        }
    }

    public record MemberResponse(UUID userId, String displayName, String email, String role,
                                 String status, Instant joinedAt) {
        static MemberResponse of(SpaceMember m, User u) {
            return new MemberResponse(
                    m.getUserId(),
                    u != null ? u.getDisplayName() : null,
                    u != null ? u.getEmail() : null,
                    m.getRole(),
                    m.getStatus(),
                    m.getJoinedAt());
        }
    }

    public record InvitationResponse(UUID spaceId, String spaceName, String spaceKind, String role,
                                     String invitedByName, String invitedByEmail) {
    }
}
