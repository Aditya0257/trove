/*
 * ============================================================================
 *  CloudflareChatClient — one place that calls a Workers AI chat model
 * ============================================================================
 *  Purpose:        POST a prompt to any Cloudflare text model and return its reply,
 *                  billing the tokens through AiUsageTracker.
 *  Business use:    shared by the router (cheap classifier) and the answerer (grounded
 *                  reply) so both draw on the same daily budget and behave consistently.
 *  Design:         model is a parameter, so callers pick the tier; usage is recorded per
 *                  call against the shared 10k/day + per-user cap.
 * ============================================================================
 */
package com.trove.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trove.extraction.AiUsageTracker;
import com.trove.extraction.NeuronRateService;
import com.trove.extraction.provider.CloudflareProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Component
public class CloudflareChatClient {

    private final CloudflareProperties cloudflare;
    private final ObjectMapper mapper;
    private final AiUsageTracker usage;
    private final NeuronRateService neuronRates;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CloudflareChatClient(CloudflareProperties cloudflare, ObjectMapper mapper,
                                AiUsageTracker usage, NeuronRateService neuronRates) {
        this.cloudflare = cloudflare;
        this.mapper = mapper;
        this.usage = usage;
        this.neuronRates = neuronRates;
    }

    /** Runs {@code prompt} on {@code model}, bills the tokens to {@code userId}, returns the text. */
    public String chat(String model, String prompt, int maxTokens, double temperature,
                        int timeoutSeconds, UUID userId) throws Exception {
        String url = "https://api.cloudflare.com/client/v4/accounts/%s/ai/run/%s"
                .formatted(cloudflare.getAccountId(), model);
        var root = mapper.createObjectNode();
        root.put("temperature", temperature);
        root.put("max_tokens", maxTokens);
        root.putArray("messages").addObject().put("role", "user").put("content", prompt);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + cloudflare.getApiToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(resp.body());
        JsonNode u = json.path("result").path("usage");
        long inTok = u.path("prompt_tokens").asLong(0);
        long outTok = u.path("completion_tokens").asLong(0);
        if (inTok + outTok > 0 && userId != null) {
            usage.record(userId, neuronRates.neuronsFor(model, inTok, outTok), inTok + outTok);
        }
        JsonNode response = json.path("result").path("response");
        return response.isTextual() ? response.asText("") : response.toString();
    }
}
