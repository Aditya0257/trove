package com.trove.service;

import com.trove.dto.GlobalCheck;
import com.trove.dto.IntegrityDtos.IntegrityReport;
import java.util.UUID;

/** Service contract for IntegrityService. */
public interface IntegrityService {
    IntegrityReport report(UUID spaceId);
    GlobalCheck globalCheck();
}
