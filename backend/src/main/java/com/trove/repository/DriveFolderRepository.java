/*
 * ============================================================================
 *  DriveFolderRepository — cache of created Drive folder ids per connection
 * ============================================================================
 */
package com.trove.repository;

import com.trove.dto.DriveFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DriveFolderRepository extends JpaRepository<DriveFolder, UUID> {
    Optional<DriveFolder> findByConnectionIdAndPath(UUID connectionId, String path);
}
