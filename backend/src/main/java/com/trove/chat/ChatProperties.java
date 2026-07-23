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
    /** Chat model that writes the grounded answer (reused from search). */
    private String chatModel = "@cf/meta/llama-3.1-8b-instruct";
    /** How many documents to retrieve as context. Keep small to bound tokens/cost. */
    private int topK = 5;
    /** Max characters of each document's text put into the prompt. */
    private int maxSnippetChars = 400;
    private int timeoutSeconds = 20;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public int getMaxSnippetChars() { return maxSnippetChars; }
    public void setMaxSnippetChars(int maxSnippetChars) { this.maxSnippetChars = maxSnippetChars; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
