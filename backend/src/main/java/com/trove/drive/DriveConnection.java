/*
 * ============================================================================
 *  DriveConnection — a space's link to its owner's Google Drive
 * ============================================================================
 *  Purpose:        maps `drive_connection`: the encrypted refresh token + the id of
 *                  the "Trove" root folder created in the owner's Drive.
 *  Business use:    each space backs up into ITS OWNER's own 15 GB Drive (per-owner
 *                  OAuth, DECISIONS.md → D17).
 *  Design:         refresh token is stored only as AES-GCM ciphertext. One row per
 *                  space (unique space_id).
 * ============================================================================
 */
package com.trove.drive;

import com.trove.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "drive_connection")
public class DriveConnection extends BaseEntity {

    @Column(name = "space_id", nullable = false, unique = true)
    private UUID spaceId;

    @Column(name = "refresh_token_enc", nullable = false)
    private String refreshTokenEnc;

    @Column(name = "root_folder_id")
    private String rootFolderId;

    @Column(name = "connected_by")
    private UUID connectedBy;

    // Which Google account this space's Drive is connected to — read from Drive's
    // about.get at connect/sync time (drive.file scope covers it). Shown in the UI so
    // an owner knows whose 15 GB backs the space; groundwork for Drive pooling (Phase 3).
    @Column(name = "google_email")
    private String googleEmail;

    @Column(name = "google_account_name")
    private String googleAccountName;

    // Cached storage quota of the Google account (bytes), read from Drive's about.get.
    // limit is null for unlimited/Workspace accounts. Refreshed on connect and each sync.
    @Column(name = "storage_limit_bytes")
    private Long storageLimitBytes;

    @Column(name = "storage_usage_bytes")
    private Long storageUsageBytes;

    @Column(name = "quota_checked_at")
    private Instant quotaCheckedAt;

    @CreationTimestamp
    @Column(name = "connected_at", nullable = false, updatable = false)
    private Instant connectedAt;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    protected DriveConnection() {
        // for JPA
    }

    public DriveConnection(UUID spaceId, String refreshTokenEnc, UUID connectedBy) {
        this.spaceId = spaceId;
        this.refreshTokenEnc = refreshTokenEnc;
        this.connectedBy = connectedBy;
    }

    public UUID getSpaceId() { return spaceId; }
    public String getRefreshTokenEnc() { return refreshTokenEnc; }
    public void setRefreshTokenEnc(String refreshTokenEnc) { this.refreshTokenEnc = refreshTokenEnc; }
    public String getRootFolderId() { return rootFolderId; }
    public void setRootFolderId(String rootFolderId) { this.rootFolderId = rootFolderId; }
    public UUID getConnectedBy() { return connectedBy; }
    public void setConnectedBy(UUID connectedBy) { this.connectedBy = connectedBy; }
    public String getGoogleEmail() { return googleEmail; }
    public void setGoogleEmail(String googleEmail) { this.googleEmail = googleEmail; }
    public String getGoogleAccountName() { return googleAccountName; }
    public void setGoogleAccountName(String googleAccountName) { this.googleAccountName = googleAccountName; }
    public Long getStorageLimitBytes() { return storageLimitBytes; }
    public void setStorageLimitBytes(Long storageLimitBytes) { this.storageLimitBytes = storageLimitBytes; }
    public Long getStorageUsageBytes() { return storageUsageBytes; }
    public void setStorageUsageBytes(Long storageUsageBytes) { this.storageUsageBytes = storageUsageBytes; }
    public Instant getQuotaCheckedAt() { return quotaCheckedAt; }
    public void setQuotaCheckedAt(Instant quotaCheckedAt) { this.quotaCheckedAt = quotaCheckedAt; }
    public Instant getConnectedAt() { return connectedAt; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }
}
