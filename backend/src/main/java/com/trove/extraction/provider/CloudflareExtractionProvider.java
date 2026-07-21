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
 *  sending the image as the int-array (uint8) Workers AI vision models accept, plus
 *  the shared prompt. Model is config-driven (CF_MODEL) — default is
 *  llama-3.2-11b-vision-instruct, which reads documents far better than llava-1.5-7b.
 *  Uses the shared ExtractionResponseParser. 429 → quota (opens breaker); other
 *  failures → transient (chain falls through). See DECISIONS.md → D9.
 *
 *  Reasoning & logic
 *  -----------------
 *  Workers AI vision models return free text under result.response (llava also fills
 *  result.description); we prompt for JSON and parse both leniently. The {image,prompt}
 *  body is accepted by both llava and the Llama vision model, so swapping CF_MODEL needs
 *  no code change. Blank creds → transient skip (not a quota outage).
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

        String body = buildRequestBody(fileBytes, mimeType, model);
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
            JsonNode response = result.path("response");
            // Three response shapes across Workers AI vision models:
            //   • Llama vision in JSON mode returns result.response as an OBJECT
            //     (the structured fields directly) — .asText() would be "", so
            //     re-serialize it back to JSON for the shared parser.
            //   • LLaVA returns free text under result.description.
            //   • Others return a plain string under result.response.
            String text;
            if (response.isObject() || response.isArray()) {
                text = response.toString();
            } else if (result.hasNonNull("description")) {
                text = result.path("description").asText("");
            } else {
                text = response.asText("");
            }
            return ExtractionResponseParser.parse(text, mapper);
        } catch (IllegalArgumentException e) {
            throw ExtractionException.transientError(LABEL, e.getMessage(), e);
        } catch (Exception e) {
            throw ExtractionException.transientError(LABEL, "Unreadable Cloudflare response: " + e.getMessage(), e);
        }
    }

    /*
     * Two Workers AI vision input shapes, chosen by model:
     *   • LLaVA (@cf/llava-*) wants a flat { image:[uint8…], prompt } — it ignores
     *     the chat "messages" schema and returns an empty response otherwise.
     *   • Llama-3.2-Vision (and other instruct vision models) want the portable
     *     OpenAI-style { messages:[{ content:[text, image_url(data-URI)] }] } — the
     *     flat image[] form yields "Unable to add image…". Using the standard
     *     content-parts schema here means a future model swap needs no code change.
     */
    private String buildRequestBody(byte[] fileBytes, String mimeType, String model) {
        ObjectNode root = mapper.createObjectNode();
        if (model != null && model.toLowerCase().contains("llava")) {
            ArrayNode image = root.putArray("image");
            for (byte b : fileBytes) {
                image.add(b & 0xFF);
            }
            root.put("prompt", ExtractionPrompt.INSTRUCTION);
            root.put("max_tokens", 1024);
            return root.toString();
        }
        String dataUri = "data:" + imageMime(mimeType) + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(fileBytes);
        ArrayNode messages = root.putArray("messages");
        ArrayNode content = messages.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", ExtractionPrompt.INSTRUCTION);
        content.addObject().put("type", "image_url")
                .putObject("image_url").put("url", dataUri);
        root.put("max_tokens", 1024);
        return root.toString();
    }

    /** Vision models need an image MIME; fall back to png for missing/non-image types. */
    private String imageMime(String mimeType) {
        return (mimeType != null && mimeType.startsWith("image/")) ? mimeType : "image/png";
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
