/*
 * ============================================================================
 *  CloudflareEmbeddingProvider — text→vector via Workers AI (bge-base)
 * ============================================================================
 *  Purpose:        real embeddings from Cloudflare's @cf/baai/bge-base-en-v1.5, using
 *                  the same account + token as extraction.
 *  Business use:    semantic index for "Ask your vault". ~6058 neurons/M tokens, i.e.
 *                  ~0.002 neurons per document — negligible against the 10k/day free tier.
 *  Solution:        POST /accounts/{id}/ai/run/{model} with {"text": "..."}; the vector
 *                  comes back as result.data[0]. Usage (if reported) is billed through
 *                  AiUsageTracker so embeddings also respect the shared daily budget.
 * ============================================================================
 */
package com.trove.integration;
import com.trove.config.ChatProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trove.service.impl.AiUsageTracker;
import com.trove.service.impl.NeuronRateService;
import com.trove.config.CloudflareProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Component
public class CloudflareEmbeddingProvider implements EmbeddingProvider {

    private final ChatProperties props;
    private final CloudflareProperties cloudflare;
    private final ObjectMapper mapper;
    private final AiUsageTracker usage;
    private final NeuronRateService neuronRates;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CloudflareEmbeddingProvider(ChatProperties props, CloudflareProperties cloudflare,
                                       ObjectMapper mapper, AiUsageTracker usage, NeuronRateService neuronRates) {
        this.props = props;
        this.cloudflare = cloudflare;
        this.mapper = mapper;
        this.usage = usage;
        this.neuronRates = neuronRates;
    }

    /** True when a Cloudflare account + token are configured. */
    public boolean isConfigured() {
        return notBlank(cloudflare.getAccountId()) && notBlank(cloudflare.getApiToken());
    }

    @Override
    public float[] embed(String text, UUID billToUserId) {
        try {
            String url = "https://api.cloudflare.com/client/v4/accounts/%s/ai/run/%s"
                    .formatted(cloudflare.getAccountId(), props.getEmbeddingModel());
            var root = mapper.createObjectNode();
            root.put("text", text == null ? "" : text);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + cloudflare.getApiToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(resp.body());
            if (!json.path("success").asBoolean(false)) {
                throw new IllegalStateException("embedding call failed: " + resp.body());
            }
            // result.data is [[...768 floats...]] for a single input.
            JsonNode data = json.path("result").path("data");
            JsonNode vec = data.isArray() && data.size() > 0 ? data.get(0) : mapper.missingNode();
            if (!vec.isArray() || vec.size() != props.getDimensions()) {
                throw new IllegalStateException("unexpected embedding shape: " + vec.size());
            }
            float[] out = new float[vec.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) vec.get(i).asDouble();
            }
            // Bill any reported tokens to the shared daily budget (input-only for embeddings).
            long tokens = json.path("result").path("usage").path("prompt_tokens").asLong(0);
            if (tokens > 0 && billToUserId != null) {
                usage.record(billToUserId, neuronRates.neuronsFor(props.getEmbeddingModel(), tokens, 0), tokens);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Cloudflare embedding failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String model() {
        return props.getEmbeddingModel();
    }

    @Override
    public int dimensions() {
        return props.getDimensions();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
