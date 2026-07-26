/*
 * ============================================================================
 *  OllamaExtractionProvider — vision extraction via a local Ollama model (free)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Sends the document image to a locally-running Ollama vision model and parses the
 *  JSON reply into an ExtractionResult.
 *
 *  Business use case
 *  -----------------
 *  The zero-dependency, zero-cost base tier: an in-house model that keeps extraction
 *  working even when every cloud free tier is exhausted. Registered as chain step
 *  'ollama', typically placed just before the stub (DECISIONS.md → D9).
 *
 *  Solution architecture
 *  ---------------------
 *  Bean name "ollama". Calls Ollama's /api/generate with format=json and the image
 *  base64-encoded, using the shared prompt + parser. If Ollama isn't running or the
 *  model is missing, it throws a transient error and the engine falls through.
 *
 *  Reasoning & logic
 *  -----------------
 *  Ollama has no per-call quota (it's local), so failures are always transient — a
 *  connection refusal just means "not available here", not "out of quota".
 * ============================================================================
 */
package com.trove.integration;
import com.trove.config.OllamaProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trove.exception.ExtractionException;
import com.trove.integration.ExtractionProvider;
import com.trove.dto.ExtractionRequest;
import com.trove.dto.ExtractionResult;
import com.trove.integration.ExtractionPrompt;
import com.trove.integration.ExtractionResponseParser;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

@Component("ollama")
public class OllamaExtractionProvider implements ExtractionProvider {

    private static final String LABEL = "ollama";

    private final OllamaProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OllamaExtractionProvider(OllamaProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public ExtractionResult extract(byte[] fileBytes, String mimeType) {
        return extract(fileBytes, mimeType, ExtractionRequest.defaults());
    }

    @Override
    public ExtractionResult extract(byte[] fileBytes, String mimeType, ExtractionRequest request) {
        String model = request.model() != null && !request.model().isBlank()
                ? request.model() : props.getDefaultModel();

        String body = buildRequestBody(model, fileBytes);
        String url = props.getEndpoint() + "/api/generate";

        HttpResponse<String> resp;
        try {
            HttpRequest httpReq = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // Connection refused / timeout: Ollama not available here.
            throw ExtractionException.transientError(LABEL, "Ollama request failed: " + e.getMessage(), e);
        }

        if (resp.statusCode() / 100 != 2) {
            throw ExtractionException.transientError(LABEL,
                    "Ollama HTTP " + resp.statusCode() + ": " + truncate(resp.body()), null);
        }

        String text = extractResponseField(resp.body());
        try {
            return ExtractionResponseParser.parse(text, mapper);
        } catch (IllegalArgumentException e) {
            throw ExtractionException.transientError(LABEL, e.getMessage(), e);
        }
    }

    /** Builds the /api/generate body: prompt + base64 image, JSON mode, no streaming. */
    private String buildRequestBody(String model, byte[] fileBytes) {
        String base64 = Base64.getEncoder().encodeToString(fileBytes);
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("prompt", ExtractionPrompt.INSTRUCTION);
        root.put("stream", false);
        root.put("format", "json");
        ArrayNode images = root.putArray("images");
        images.add(base64);
        ObjectNode options = root.putObject("options");
        options.put("temperature", 0);
        return root.toString();
    }

    /** Ollama returns the model text under the "response" field. */
    private String extractResponseField(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            return root.path("response").asText("");
        } catch (Exception e) {
            throw ExtractionException.transientError(LABEL, "Unreadable Ollama response: " + e.getMessage(), e);
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
