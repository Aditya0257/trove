/*
 * ============================================================================
 *  IngestProperties — shared secret gating the ingestion webhooks
 * ============================================================================
 *  Purpose:        binds trove.ingest.* (shared secret, enabled toggle).
 *  Business use:    email/WhatsApp webhooks are public endpoints (external services
 *                  call them), so a shared secret keeps random callers out.
 *  Design:         a single shared secret for now; per-space ingest tokens/addresses
 *                  are a later refinement. Set via env in prod.
 * ============================================================================
 */
package com.trove.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.ingest")
public class IngestProperties {

    private boolean enabled = true;
    private String secret = "";
    /** Domain used to render a space's ingest address (trove+<token>@<domain>). */
    private String addressDomain = "ingest.trove.local";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public String getAddressDomain() { return addressDomain; }
    public void setAddressDomain(String addressDomain) { this.addressDomain = addressDomain; }
}
