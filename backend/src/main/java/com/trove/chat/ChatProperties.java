/*
 * ============================================================================
 *  ChatProperties — configuration for "Ask your vault" (RAG)
 * ============================================================================
 *  Purpose:        one place for the models + retrieval knobs, so cost and behaviour
 *                  are tunable without code changes (all under trove.chat.*).
 *  Business use:    grounded Q&A over the user's documents, kept inside the free tier.
 *  Design:         embedding + chat models are Cloudflare Workers AI by default (same
 *                  account/token as extraction). topK + snippet caps bound the tokens
 *                  each question spends. dimensions must match the DDL vector(768).
 * ============================================================================
 */
package com.trove.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.chat")
public class ChatProperties {

    /** Master switch for the assistant (retrieval always works; this gates the LLM answer). */
    private boolean enabled = true;
    /** Embedding model — bge-base-en-v1.5 → 768 dims (must match V19's vector(768)). */
    private String embeddingModel = "@cf/baai/bge-base-en-v1.5";
    private int dimensions = 768;
    /** Chat model that writes the grounded answer (fallback / single-model default). */
    private String chatModel = "@cf/meta/llama-3.1-8b-instruct";

    // ── model routing ────────────────────────────────────────────────────────
    /** When true, a cheap classifier picks the answer model per query (cost-aware). */
    private boolean routingEnabled = true;
    /** Tiny, near-free classifier that labels a question simple vs complex (~0.4 neurons). */
    private String routerModel = "@cf/meta/llama-3.2-1b-instruct";
    /** Cheap model for simple single-fact lookups (~3.8× cheaper than 8b). */
    private String lightModel = "@cf/meta/llama-3.2-3b-instruct";
    /** Capable model for reasoning/aggregation/comparison questions. */
    private String standardModel = "@cf/meta/llama-3.1-8b-instruct";
    /** Once the shared daily budget is this fraction spent, force the light model so the
     *  free tier stretches across more users (0..1; 1 disables the downgrade). */
    private double budgetDowngradeFraction = 0.75;
    /** How many documents to retrieve as context. Keep small to bound tokens/cost. */
    private int topK = 5;
    /** Max characters of each document's text put into the prompt. */
    private int maxSnippetChars = 400;
    private int timeoutSeconds = 20;
    /**
     * Relevance floor: drop retrieved documents whose cosine DISTANCE (pgvector {@code <=>},
     * range 0=identical .. 2=opposite) is above this. Without it, search always returns the
     * topK nearest documents however far away they are, so an off-topic question (e.g. "my
     * reminders" when nothing matches) still surfaces weak, unrelated "sources" under a
     * refusal - which reads as broken. Tuned against real bge-base-en-v1.5 embeddings: genuine
     * matches sit well below this, clearly-unrelated ones above. 2.0 effectively disables it.
     *
     * Note: bge-base distances are compressed (genuine matches ~0.25-0.40, pure junk ~0.47-0.50),
     * so this is a loose safety net for extreme outliers, not the main filter. The primary
     * relevance signal is the model's own citations - see VaultChatService: when the grounded
     * answer cites no document, we treat that as "nothing relevant" and return no sources.
     */
    private double maxDistance = 0.6;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }
    public boolean isRoutingEnabled() { return routingEnabled; }
    public void setRoutingEnabled(boolean routingEnabled) { this.routingEnabled = routingEnabled; }
    public String getRouterModel() { return routerModel; }
    public void setRouterModel(String routerModel) { this.routerModel = routerModel; }
    public String getLightModel() { return lightModel; }
    public void setLightModel(String lightModel) { this.lightModel = lightModel; }
    public String getStandardModel() { return standardModel; }
    public void setStandardModel(String standardModel) { this.standardModel = standardModel; }
    public double getBudgetDowngradeFraction() { return budgetDowngradeFraction; }
    public void setBudgetDowngradeFraction(double v) { this.budgetDowngradeFraction = v; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public int getMaxSnippetChars() { return maxSnippetChars; }
    public void setMaxSnippetChars(int maxSnippetChars) { this.maxSnippetChars = maxSnippetChars; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public double getMaxDistance() { return maxDistance; }
    public void setMaxDistance(double maxDistance) { this.maxDistance = maxDistance; }
}
