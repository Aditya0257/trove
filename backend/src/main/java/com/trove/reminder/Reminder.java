/*
 * ============================================================================
 *  Reminder — a scheduled nudge tied to a space (and optionally a document)
 * ============================================================================
 *  Purpose:        maps the `reminder` table (DESIGN.md §2): what to remind about
 *                  and when.
 *  Business use:    "electricity bill due in 3 days", "policy renews next month",
 *                  "warranty expires soon" — the value in DESIGN build order item 5.
 *  Design:         document_id is nullable (a reminder can be standalone). status
 *                  defaults to pending. remind_on is the date the scheduler fires on.
 * ============================================================================
 */
package com.trove.reminder;

import com.trove.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "reminder")
public class Reminder extends BaseEntity {

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    @Column(name = "type", nullable = false)
    private String type; // due | renewal | warranty_expiry

    @Column(name = "title")
    private String title; // optional human label, e.g. "Rent - pay landlord"

    @Column(name = "remind_on", nullable = false)
    private LocalDate remindOn;

    @Column(name = "recurrence", nullable = false)
    private String recurrence = ReminderRecurrence.NONE; // none | weekly | monthly | quarterly | yearly

    @Column(name = "status", nullable = false)
    private String status = ReminderStatus.PENDING;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Reminder() {
        // for JPA
    }

    public Reminder(UUID spaceId, UUID documentId, String type, LocalDate remindOn) {
        this.spaceId = spaceId;
        this.documentId = documentId;
        this.type = type;
        this.remindOn = remindOn;
    }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public UUID getSpaceId() { return spaceId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public LocalDate getRemindOn() { return remindOn; }
    public void setRemindOn(LocalDate remindOn) { this.remindOn = remindOn; }
    public String getRecurrence() { return recurrence; }
    public void setRecurrence(String recurrence) { this.recurrence = recurrence; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
