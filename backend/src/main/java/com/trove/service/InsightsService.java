package com.trove.service;

import com.trove.dto.ExpiringItem;
import com.trove.dto.RecurringGroup;
import java.util.List;
import java.util.UUID;

/** Service contract for InsightsService. */
public interface InsightsService {
    List<ExpiringItem> expiring(UUID spaceId, UUID userId, int withinDays);
    List<RecurringGroup> recurring(UUID spaceId, UUID userId);
}
