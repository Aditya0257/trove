/*
 * ============================================================================
 *  UsageController — free-tier usage overview for the Developer gauge
 * ============================================================================
 *  Purpose:  one endpoint the Developer drawer polls to render every free-tier
 *            meter (AI, email, object storage, database, mirror) with the next
 *            daily-reset instant. Supersedes the AI-only /api/ai-usage for the gauge.
 * ============================================================================
 */
package com.trove.controllers;

import com.trove.dto.UsageOverview;
import com.trove.security.CurrentUser;
import com.trove.service.UsageService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usage;
    private final CurrentUser currentUser;

    public UsageController(UsageService usage, CurrentUser currentUser) {
        this.usage = usage;
        this.currentUser = currentUser;
    }

    @GetMapping
    public UsageOverview overview() {
        return usage.overview(currentUser.requireUserId());
    }
}
