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
package com.trove.controllers;
import com.trove.service.AccountDeletionService;
import com.trove.entity.User;
import com.trove.repository.UserRepository;
import com.trove.service.UserService;

import com.trove.exception.ForbiddenException;
import com.trove.exception.NotFoundException;
import com.trove.security.CurrentUser;
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
@RequestMapping("/api/admin")
public class AdminController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final AccountDeletionService accountDeletionService;
    private final CurrentUser currentUser;

    public AdminController(UserService userService, UserRepository userRepository,
                           AccountDeletionService accountDeletionService, CurrentUser currentUser) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.accountDeletionService = accountDeletionService;
        this.currentUser = currentUser;
    }

    /** All accounts (admin view), for the delete-account picker on the profile screen. */
    @GetMapping("/users")
    public List<AdminUser> users() {
        requireAdmin();
        return userRepository.findAll().stream()
                .map(u -> new AdminUser(u.getId(), u.getEmail(), u.getDisplayName(),
                        u.getStatus(), userService.isAdmin(u), u.getCreatedAt()))
                .sorted((a, b) -> a.email().compareToIgnoreCase(b.email()))
                .toList();
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

    /**
     * Permanently delete an account and ALL its data (documents, spaces, everything). Admin
     * only, and heavily guarded because it is irreversible: the caller cannot delete their own
     * account, cannot delete another admin (remove them from config first), and must echo the
     * exact email as a type-to-confirm safety, mirroring the in-app confirmation dialog.
     */
    @PostMapping("/users/{id}/delete")
    public void deleteUser(@PathVariable("id") UUID id, @RequestBody DeleteRequest req) {
        User me = requireAdmin();
        if (me.getId().equals(id)) {
            throw new ForbiddenException("You cannot delete your own admin account here");
        }
        User target = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Account not found"));
        if (userService.isAdmin(target)) {
            throw new ForbiddenException("Admin accounts cannot be deleted here. Remove the email from the admin config first.");
        }
        String confirm = req == null ? null : req.confirmEmail();
        if (confirm == null || !confirm.trim().equalsIgnoreCase(target.getEmail())) {
            throw new IllegalArgumentException("The confirmation email does not match this account");
        }
        accountDeletionService.deleteAccount(id);
    }

    private User requireAdmin() {
        User me = userRepository.findById(currentUser.requireUserId())
                .orElseThrow(() -> new NotFoundException("Account not found"));
        if (!userService.isAdmin(me)) {
            throw new ForbiddenException("Admin only");
        }
        return me;
    }

    public record PendingUser(UUID id, String email, String displayName, Instant requestedAt) {
    }

    public record AdminUser(UUID id, String email, String displayName, String status,
                            boolean admin, Instant createdAt) {
    }

    public record DeleteRequest(String confirmEmail) {
    }
}
