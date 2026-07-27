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
package com.trove.controllers;
import com.trove.entity.Reminder;
import com.trove.service.ReminderService;

import com.trove.security.CurrentUser;
import com.trove.service.SpaceService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
        List<Reminder> reminders = reminderService.list(space, user, status);
        // One batch lookup of the linked file names, so the client shows "Payment due - rent.pdf"
        // without fetching the whole document list on every visit to the Reminders page.
        Map<UUID, String> names = reminderService.documentFilenames(reminders);
        return reminders.stream().map(r -> ReminderResponse.of(r, names.get(r.getDocumentId()))).toList();
    }

    /** Create a manual reminder. */
    @PostMapping
    public ResponseEntity<ReminderResponse> create(
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestBody CreateReminderRequest req) {
        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        Reminder r = reminderService.create(space, user, req.documentId(), req.type(), req.title(),
                req.remindOn(), req.recurrence());
        return ResponseEntity.status(HttpStatus.CREATED).body(ReminderResponse.of(r));
    }

    /** Edit a reminder (type, title, date, recurrence, linked document). */
    @PatchMapping("/{id}")
    public ReminderResponse update(@PathVariable UUID id, @RequestBody UpdateReminderRequest req) {
        Reminder r = reminderService.update(id, currentUser.requireUserId(), req.type(), req.title(),
                req.remindOn(), req.recurrence(), req.documentId());
        return ReminderResponse.of(r);
    }

    /** Snooze a reminder to fire {@code days} from today (days=0 reopens it as due now). */
    @PostMapping("/{id}/snooze")
    public ReminderResponse snooze(@PathVariable UUID id,
                                   @RequestParam(value = "days", defaultValue = "1") int days) {
        return ReminderResponse.of(reminderService.snooze(id, currentUser.requireUserId(), days));
    }

    /** Mark a reminder done (and, if recurring, schedule the next occurrence). */
    @PostMapping("/{id}/done")
    public ReminderResponse done(@PathVariable UUID id) {
        return ReminderResponse.of(reminderService.markDone(id, currentUser.requireUserId()));
    }

    /** Dismiss a reminder - "never mind". */
    @PostMapping("/{id}/dismiss")
    public ReminderResponse dismiss(@PathVariable UUID id) {
        return ReminderResponse.of(reminderService.dismiss(id, currentUser.requireUserId()));
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record CreateReminderRequest(UUID documentId, @NotBlank String type, String title,
                                        @NotNull LocalDate remindOn, String recurrence) {
    }

    public record UpdateReminderRequest(UUID documentId, @NotBlank String type, String title,
                                        @NotNull LocalDate remindOn, String recurrence) {
    }

    public record ReminderResponse(UUID id, UUID documentId, UUID spaceId, String type, String title,
                                   LocalDate remindOn, String recurrence, String status,
                                   Instant completedAt, Instant createdAt, String documentFilename) {
        static ReminderResponse of(Reminder r) {
            return of(r, null);
        }

        /** {@code documentFilename} lets the list carry each reminder's linked file name, so a
         *  client showing reminders needs no separate fetch of the whole document list. */
        static ReminderResponse of(Reminder r, String documentFilename) {
            return new ReminderResponse(r.getId(), r.getDocumentId(), r.getSpaceId(), r.getType(),
                    r.getTitle(), r.getRemindOn(), r.getRecurrence(), r.getStatus(),
                    r.getCompletedAt(), r.getCreatedAt(), documentFilename);
        }
    }
}
