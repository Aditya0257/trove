/*
 * ============================================================================
 *  ReminderController — REST surface for reminders
 * ============================================================================
 *  Purpose:        list reminders, create a manual one, and dismiss one.
 *  Business use:    lets clients show upcoming due/renewal/warranty nudges and let
 *                  the user clear them.
 *  Design:         /api/reminders (authenticated); space defaults to the caller's
 *                  personal space; membership/role enforced in ReminderService.
 * ============================================================================
 */
package com.trove.reminder;

import com.trove.common.security.CurrentUser;
import com.trove.space.SpaceService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    private final ReminderService reminderService;
    private final SpaceService spaceService;
    private final CurrentUser currentUser;

    public ReminderController(ReminderService reminderService, SpaceService spaceService,
                             CurrentUser currentUser) {
        this.reminderService = reminderService;
        this.spaceService = spaceService;
        this.currentUser = currentUser;
    }

    /** List reminders in a space (defaults to personal), optionally by status. */
    @GetMapping
    public List<ReminderResponse> list(
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "status", required = false) String status) {
        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        return reminderService.list(space, user, status).stream().map(ReminderResponse::of).toList();
    }

    /** Create a manual reminder. */
    @PostMapping
    public ResponseEntity<ReminderResponse> create(
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestBody CreateReminderRequest req) {
        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        Reminder r = reminderService.create(space, user, req.documentId(), req.type(), req.remindOn());
        return ResponseEntity.status(HttpStatus.CREATED).body(ReminderResponse.of(r));
    }

    /** Dismiss a reminder. */
    @PostMapping("/{id}/dismiss")
    public ReminderResponse dismiss(@PathVariable UUID id) {
        return ReminderResponse.of(reminderService.dismiss(id, currentUser.requireUserId()));
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record CreateReminderRequest(UUID documentId, @NotBlank String type,
                                        @NotNull LocalDate remindOn) {
    }

    public record ReminderResponse(UUID id, UUID documentId, UUID spaceId, String type,
                                   LocalDate remindOn, String status, Instant createdAt) {
        static ReminderResponse of(Reminder r) {
            return new ReminderResponse(r.getId(), r.getDocumentId(), r.getSpaceId(), r.getType(),
                    r.getRemindOn(), r.getStatus(), r.getCreatedAt());
        }
    }
}
