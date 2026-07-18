/*
 * ============================================================================
 *  DriveFolder — cached id of a folder we created in Drive
 * ============================================================================
 *  Purpose:        maps `drive_folder`: remembers the Drive folder id for a relative
 *                  path (e.g. 'electricity/2026-07') per space.
 *  Business use:    with drive.file scope we can only see files we created; caching
 *                  ids means we don't recreate the Trove/{category}/{month} tree.
 *  Design:         unique (space_id, path).
 * ============================================================================
 */
package com.trove.drive;

import com.trove.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "drive_folder")
public class DriveFolder extends BaseEntity {

    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "folder_id", nullable = false)
    private String folderId;

    protected DriveFolder() {
        // for JPA
    }

    public DriveFolder(UUID spaceId, String path, String folderId) {
        this.spaceId = spaceId;
        this.path = path;
        this.folderId = folderId;
    }

    public UUID getSpaceId() { return spaceId; }
    public String getPath() { return path; }
    public String getFolderId() { return folderId; }
}
