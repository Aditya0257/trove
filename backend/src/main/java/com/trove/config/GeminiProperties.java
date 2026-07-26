/*
 * ============================================================================
 *  GeminiProperties — config for the Google Gemini extraction provider
 * ============================================================================
 *  Purpose:        binds trove.extraction.providers.gemini.* (API key, endpoint,
 *                  default model, timeout).
 *  Business use:    Gemini's free tier is a strong zero-cost primary for vision
 *                  extraction; keys come from env so nothing is committed.
 *  Design:         apiKey blank => provider self-skips (engine falls to next step).
 * ============================================================================
 */
package com.trove.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.extraction.providers.gemini")
public class GeminiProperties {

    private String apiKey = "";
    private String endpoint = "https://generativelanguage.googleapis.com/v1beta";
    private String defaultModel = "gemini-2.0-flash";
    private int timeoutSeconds = 60;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
