/*
 * ============================================================================
 *  CloudflareExtractionProvider — vision extraction via Cloudflare Workers AI
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Sends the document image to a Cloudflare Workers AI vision model and parses the
 *  reply into an ExtractionResult.
 *
 *  Business use case
 *  -----------------
 *  The recommended FREE + HOSTED extraction path for a cloud deployment: Workers AI
 *  has a permanent free daily allowance and needs no server of your own (unlike
 *  Ollama). Registered as chain step 'cloudflare'.
 *
 *  Solution architecture
 *  ---------------------
 *  Bean "cloudflare". Calls POST /accounts/{id}/ai/run/{model} with a Bearer token,
 *  sending the image as the int-array Workers AI expects for LLaVA, plus the shared
 *  prompt. Uses the shared ExtractionResponseParser. 429 → quota (opens breaker);
 *  other failures → transient (chain falls through). See DECISIONS.md → D9.
 *
 *  Reasoning & logic
 *  -----------------
 *  LLaVA on Workers AI returns free text under result.description/response; we prompt
 *  for JSON and parse leniently. Blank creds → transient skip (not a quota outage).
 * ============================================================================
 */
package com.trove.extraction.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trove.extraction.ExtractionException;
import com.trove.extraction.ExtractionProvider;
import com.trove.extraction.ExtractionRequest;
import com.trove.extraction.ExtractionResult;
import com.trove.extraction.support.ExtractionPrompt;
import com.trove.extraction.support.ExtractionResponseParser;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component("cloudflare")
public class CloudflareExtractionProvider implements ExtractionProvider {

    private static final String LABEL = "cloudflare";

    private final CloudflareProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public CloudflareExtractionProvider(CloudflareProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public ExtractionResult extract(byte[] fileBytes, String mimeType) {
        return extract(fileBytes, mimeType, ExtractionRequest.defaults());
    }

    @Override
    public ExtractionResult extract(byte[] fileBytes, String mimeType, ExtractionRequest request) {
        if (props.getAccountId().isBlank() || props.getApiToken().isBlank()) {
            throw ExtractionException.transientError(LABEL, "Cloudflare Workers AI not configured", null);
        }
        String model = request.model() != null && !request.model().isBlank()
                ? request.model() : props.getDefaultModel();
        String url = "https://api.cloudflare.com/client/v4/accounts/%s/ai/run/%s"
                .formatted(props.getAccountId(), model);

        String body = buildRequestBody(fileBytes);
        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + props.getApiToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw ExtractionException.transientError(LABEL, "Cloudflare request failed: " + e.getMessage(), e);
        }

        if (resp.statusCode() == 429) {
            throw ExtractionException.quota(LABEL, "Cloudflare Workers AI rate limit (429)", null);
        }
        if (resp.statusCode() / 100 != 2) {
            throw ExtractionException.transientError(LABEL,
                    "Cloudflare HTTP " + resp.statusCode() + ": " + truncate(resp.body()), null);
        }

        try {
            JsonNode root = mapper.readTree(resp.body());
            JsonNode result = root.path("result");
            String text = result.has("description") ? result.path("description").asText()
                    : result.path("response").asText("");
            return ExtractionResponseParser.parse(text, mapper);
        } catch (IllegalArgumentException e) {
            throw ExtractionException.transientError(LABEL, e.getMessage(), e);
        } catch (Exception e) {
            throw ExtractionException.transientError(LABEL, "Unreadable Cloudflare response: " + e.getMessage(), e);
        }
    }

    /** Workers AI LLaVA expects the image as an array of byte values (0..255). */
    private String buildRequestBody(byte[] fileBytes) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode image = root.putArray("image");
        for (byte b : fileBytes) {
            image.add(b & 0xFF);
        }
        root.put("prompt", ExtractionPrompt.INSTRUCTION);
        root.put("max_tokens", 1024);
        return root.toString();
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
