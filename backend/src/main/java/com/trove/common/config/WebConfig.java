/*
 * ============================================================================
 *  WebConfig — CORS for local clients
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Allows the (future) Angular web client and local tools to call the API from a
 *  browser during development.
 *
 *  Business use case
 *  -----------------
 *  Slice 1 is exercised with curl (no browser origin needed), but the web/mobile
 *  clients are the real consumers. A permissive dev CORS policy removes a common
 *  friction point when they arrive.
 *
 *  Solution architecture
 *  ---------------------
 *  Dev-only permissive policy. Prod should tighten allowedOrigins to the real
 *  client hosts — flagged here so it is not forgotten when auth/clients land.
 *
 *  Reasoning & logic
 *  -----------------
 *  Applied to /api/** only, matching the controller base path.
 * ============================================================================
 */
package com.trove.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // DEV policy — tighten allowedOrigins for production clients later.
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
