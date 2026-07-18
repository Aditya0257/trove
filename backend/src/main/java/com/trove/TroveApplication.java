/*
 * ============================================================================
 *  TroveApplication — Spring Boot entry point
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Boots the Trove backend: component scanning under com.trove, auto-config for
 *  web + JPA + Flyway, and enables the two cross-cutting capabilities Slice 1
 *  relies on — @Async (extraction offloading) and @Scheduled (extraction
 *  reconciler sweep).
 *
 *  Business use case
 *  -----------------
 *  This is the single stateless process the brief describes: one jar on an Oracle
 *  Always-Free box. Nothing durable lives here — restart/redeploy is safe.
 *
 *  Solution architecture
 *  ---------------------
 *  @EnableAsync   → activates the bounded extraction executor (see AsyncConfig)
 *                   so uploads return immediately and extraction runs off-thread.
 *  @EnableScheduling → activates ExtractionReconciler's periodic crash-recovery
 *                   sweep. Both underpin the at-least-once guarantee (D3).
 *
 *  Reasoning & logic
 *  -----------------
 *  Kept intentionally thin — configuration lives in the common/ package so this
 *  class stays a pure bootstrap.
 * ============================================================================
 */
package com.trove;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class TroveApplication {

    public static void main(String[] args) {
        SpringApplication.run(TroveApplication.class, args);
    }
}
