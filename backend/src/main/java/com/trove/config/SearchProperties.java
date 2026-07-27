/*
 * ============================================================================
 *  SearchProperties — optional LLM-backed natural-language parsing
 * ============================================================================
 *  Purpose:        binds trove.search.llm.* — whether to parse queries with an LLM,
 *                  and which provider/model.
 *  Business use:    turns "top 10 expensive shopping bills" into real filters
 *                  (category + sort-by-amount + limit) that the rule-based parser
 *                  can't express. Falls back to rules when disabled or on error.
 *  Design:         provider reuses the extraction providers' creds (ollama endpoint /
 *                  cloudflare account+token). Disabled by default.
 * ============================================================================
 */
package com.trove.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.search")
public class SearchProperties {

    private final Llm llm = new Llm();

    public Llm getLlm() { return llm; }

    public static class Llm {
        /** Use an LLM to parse queries into filters (else rule-based only). */
        private boolean enabled = false;
        /** 'ollama' (local) or 'cloudflare' (hosted). */
        private String provider = "ollama";
        /** A TEXT model (no vision needed): e.g. llama3, or @cf/meta/llama-3.1-8b-instruct. */
        private String model = "llama3";
        private int timeoutSeconds = 30;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }
}
