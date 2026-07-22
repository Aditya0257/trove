/*
 * ============================================================================
 *  AiUsageController — today's AI consumption for the Developer gauge
 * ============================================================================
 *  Purpose:        exposes the shared daily AI budget and how much has been spent —
 *                  globally (all users, the number that counts toward the free tier)
 *                  and by the calling user — in both neurons (the billed unit) and
 *                  tokens (the human figure).
 *  Business use:    powers the two-bar gauge; makes the shared free-tier limit and each
 *                  user's slice visible and honest.
 * ============================================================================
 */
package com.trove.extraction;

import com.trove.common.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-usage")
public class AiUsageController {

    private final AiUsageTracker usage;
    private final CurrentUser currentUser;

    public AiUsageController(AiUsageTracker usage, CurrentUser currentUser) {
        this.usage = usage;
        this.currentUser = currentUser;
    }

    @GetMapping
    public UsageResponse today() {
        AiUsageTracker.Usage global = usage.globalToday();
        AiUsageTracker.Usage mine = usage.userToday(currentUser.requireUserId());
        return new UsageResponse(
                AiUsageTracker.DAILY_NEURON_LIMIT,
                new UsageDto(round(global.neurons()), global.tokens()),
                new UsageDto(round(mine.neurons()), mine.tokens()));
    }

    private static double round(double n) {
        return Math.round(n * 100.0) / 100.0;
    }

    public record UsageResponse(int limitNeurons, UsageDto global, UsageDto user) {
    }

    public record UsageDto(double neurons, long tokens) {
    }
}
