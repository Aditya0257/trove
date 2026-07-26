/*
 * ============================================================================
 *  CloudflareProperties — config for the Cloudflare Workers AI provider
 * ============================================================================
 *  Purpose:        binds trove.extraction.providers.cloudflare.* (account id, API
 *                  token, vision model, timeout).
 *  Business use:    Cloudflare Workers AI is the recommended FREE, HOSTED vision
 *                  provider (free daily allowance, no server to run) — the right fit
 *                  for a cloud deployment where Ollama (local-only) can't run and
 *                  Gemini's free tier may be zero.
 *  Design:         blank account-id/token → provider self-skips (chain falls through).
 * ============================================================================
 */
package com.trove.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.extraction.providers.cloudflare")
public class CloudflareProperties {

    private String accountId = "";
    private String apiToken = "";
    private String defaultModel = "@cf/llava-hf/llava-1.5-7b-hf";
    private int timeoutSeconds = 60;

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
