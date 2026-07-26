/*
 * ============================================================================
 *  BackupRun — an observability record of one backup/export/restore run
 * ============================================================================
 *  Purpose:        maps the `backup_run` table (DESIGN.md §2): what ran, when, and
 *                  whether it succeeded, plus where the artifact landed.
 *  Business use:    the core principle is "lose the host, lose ZERO documents" — this
 *                  table is how we PROVE the safety mechanisms actually run.
 *  Design:         started_at managed by Hibernate; finished_at/status/detail set on
 *                  completion. kind/status are free text (see BackupKind/BackupStatus).
 * ============================================================================
 */
package com.trove.entity;

import com.trove.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "backup_run")
public class BackupRun extends BaseEntity {

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "location")
    private String location;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "detail")
    private String detail;

    protected BackupRun() {
        // for JPA
    }

    public BackupRun(String kind, String status) {
        this.kind = kind;
        this.status = status;
    }

    public String getKind() { return kind; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
