/*
 * ============================================================================
 *  UsageService — assembles the free-tier usage overview for the gauge
 * ============================================================================
 */
package com.trove.service;

import com.trove.dto.UsageOverview;

import java.util.UUID;

public interface UsageService {

    /** The current free-tier usage across all backing services, for the given user. */
    UsageOverview overview(UUID userId);
}
