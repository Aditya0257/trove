/*
 * ============================================================================
 *  AdminController - the sole admin approves/declines new sign-ups
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Lists accounts awaiting approval and lets the admin approve or decline them.
 *
 *  Business use case
 *  -----------------
 *  Closed registration for a private circle: nobody new can sign in until the admin
 *  says so.
 *
 *  Solution architecture
 *  ---------------------
 *  Base path /api/admin (authenticated). Every method additionally requires that the
 *  caller IS the configured admin (UserService.isAdmin) - otherwise 403. The admin is
 *  identified by trove.admin.email, so there is no privileged flag to leak or misassign.
 * ============================================================================
 */
package com.trove.auth;

import com.trove.common.error.ForbiddenException;
import com.trove.common.error.NotFoundException;
import com.trove.common.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public AdminController(UserService userService, UserRepository userRepository, CurrentUser currentUser) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    /** Accounts awaiting approval, oldest first. Admin only. */
    @GetMapping("/pending")
    public List<PendingUser> pending() {
        requireAdmin();
        return userService.pendingUsers().stream()
                .map(u -> new PendingUser(u.getId(), u.getEmail(), u.getDisplayName(), u.getCreatedAt()))
                .toList();
    }

    /** Approve a pending account (it can sign in; the user is emailed). Admin only. */
    @PostMapping("/users/{id}/approve")
    public void approve(@PathVariable("id") UUID id) {
        requireAdmin();
        userService.approve(id);
    }

    /** Decline a pending account. Admin only. */
    @PostMapping("/users/{id}/reject")
    public void reject(@PathVariable("id") UUID id) {
        requireAdmin();
        userService.reject(id);
    }

    private void requireAdmin() {
        User me = userRepository.findById(currentUser.requireUserId())
                .orElseThrow(() -> new NotFoundException("Account not found"));
        if (!userService.isAdmin(me)) {
            throw new ForbiddenException("Admin only");
        }
    }

    public record PendingUser(UUID id, String email, String displayName, Instant requestedAt) {
    }
}
