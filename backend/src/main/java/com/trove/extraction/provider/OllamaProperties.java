/*
 * ============================================================================
 *  OllamaProperties — config for the local Ollama extraction provider
 * ============================================================================
 *  Purpose:        binds trove.extraction.providers.ollama.* (endpoint, default
 *                  vision model, timeout).
 *  Business use:    a fully free, in-house base tier — runs a local vision model so
 *                  extraction keeps working even with no cloud quota at all.
 *  Design:         if Ollama isn't running, the provider throws a transient error
 *                  and the engine simply falls through (or up) the chain.
 * ============================================================================
 */
package com.trove.extraction.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.extraction.providers.ollama")
public class OllamaProperties {

    private String endpoint = "http://localhost:11434";
    private String defaultModel = "moondream";
    private int timeoutSeconds = 120;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
