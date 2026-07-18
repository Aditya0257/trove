/*
 * ============================================================================
 *  GeminiExtractionProvider — vision extraction via Google Gemini (free tier)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Sends the document image/PDF to a Gemini vision model with the shared prompt and
 *  parses the JSON reply into an ExtractionResult.
 *
 *  Business use case
 *  -----------------
 *  Gemini's free tier is a strong zero-cost primary for reading bills/receipts at
 *  Trove's scale (~1k docs/day). Registered as chain step 'gemini', it is tried
 *  first when configured and falls through to the next step on quota/errors.
 *
 *  Solution architecture
 *  ---------------------
 *  Bean name "gemini" (chain step provider). Uses the JDK HttpClient (no extra deps)
 *  and the shared ExtractionPrompt + ExtractionResponseParser. Maps HTTP 429 to a
 *  quota ExtractionException (opens the breaker); other failures are transient.
 *  See DECISIONS.md → D9.
 *
 *  Reasoning & logic
 *  -----------------
 *  A blank API key means "not configured" → transient skip, so the engine simply
 *  moves to the next step without treating it as a quota outage.
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
import java.util.Base64;

@Component("gemini")
public class GeminiExtractionProvider implements ExtractionProvider {

    private static final String LABEL = "gemini";

    private final GeminiProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public GeminiExtractionProvider(GeminiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ExtractionResult extract(byte[] fileBytes, String mimeType) {
        return extract(fileBytes, mimeType, ExtractionRequest.defaults());
    }

    @Override
    public ExtractionResult extract(byte[] fileBytes, String mimeType, ExtractionRequest request) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw ExtractionException.transientError(LABEL, "Gemini API key not configured", null);
        }
        String model = request.model() != null && !request.model().isBlank()
                ? request.model() : props.getDefaultModel();

        String body = buildRequestBody(fileBytes, mimeType);
        String url = "%s/models/%s:generateContent?key=%s".formatted(
                props.getEndpoint(), model, props.getApiKey());

        HttpResponse<String> resp;
        try {
            HttpRequest httpReq = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw ExtractionException.transientError(LABEL, "Gemini request failed: " + e.getMessage(), e);
        }

        if (resp.statusCode() == 429) {
            throw ExtractionException.quota(LABEL, "Gemini rate limit / quota exceeded (429)", null);
        }
        if (resp.statusCode() / 100 != 2) {
            throw ExtractionException.transientError(LABEL,
                    "Gemini HTTP " + resp.statusCode() + ": " + truncate(resp.body()), null);
        }

        String text = extractText(resp.body());
        try {
            return ExtractionResponseParser.parse(text, mapper);
        } catch (IllegalArgumentException e) {
            throw ExtractionException.transientError(LABEL, e.getMessage(), e);
        }
    }

    /** Builds the generateContent body: prompt text + inline base64 file. */
    private String buildRequestBody(byte[] fileBytes, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(fileBytes);
        ObjectNode root = mapper.createObjectNode();

        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", ExtractionPrompt.INSTRUCTION);
        ObjectNode inline = parts.addObject().putObject("inline_data");
        inline.put("mime_type", mimeType != null ? mimeType : "application/octet-stream");
        inline.put("data", base64);

        ObjectNode gen = root.putObject("generationConfig");
        gen.put("temperature", 0);
        gen.put("response_mime_type", "application/json");

        return root.toString();
    }

    /** Pulls candidates[0].content.parts[0].text out of the Gemini response. */
    private String extractText(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            StringBuilder sb = new StringBuilder();
            if (parts.isArray()) {
                for (JsonNode p : parts) {
                    sb.append(p.path("text").asText(""));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            throw ExtractionException.transientError(LABEL, "Unreadable Gemini response: " + e.getMessage(), e);
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
