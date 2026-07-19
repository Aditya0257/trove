/*
 * ============================================================================
 *  HealthController — public liveness endpoint
 * ============================================================================
 *  Purpose:        an unauthenticated GET /api/health returning {status: UP} for
 *                  load balancers, uptime monitors, and the reverse proxy.
 *  Business use:    hosting basics — the platform/Caddy/uptime check needs a cheap,
 *                  auth-free endpoint to know the app is alive.
 *  Design:         permitted in SecurityConfig; does no DB/IO work (pure liveness).
 * ============================================================================
 */
package com.trove.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Map<String, String> health() {
        return Map.of("status", "UP", "app", "trove");
    }
}
