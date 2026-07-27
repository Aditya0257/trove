package com.trove.service;

import com.trove.dto.DriveSyncSummary;
import com.trove.entity.DriveConnection;
import java.util.List;
import java.util.UUID;

/** Service contract for DriveSyncService. */
public interface DriveSyncService {
    void storeConnection(UUID spaceId, UUID userId, String refreshToken);
    List<DriveConnection> connections(UUID spaceId);
    long troveBytesForConnection(UUID connectionId);
    boolean isConnected(UUID spaceId);
    String mode(UUID spaceId);
    void setMode(UUID spaceId, String mode);
    void activate(UUID spaceId, UUID connectionId);
    void disconnect(UUID spaceId, UUID connectionId);
    List<UUID> connectedSpaceIds();
    DriveSyncSummary sync(UUID spaceId);
    void moveToDeletedFolder(UUID documentId);
    void moveOutOfDeletedFolder(UUID documentId);
    void deleteFromDrives(UUID documentId);
}
