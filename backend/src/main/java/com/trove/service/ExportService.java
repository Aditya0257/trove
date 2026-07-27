package com.trove.service;

import java.util.UUID;

/** Service contract for ExportService. */
public interface ExportService {
    byte[] exportSpace(UUID spaceId, UUID userId);
}
