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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import javax.imageio.ImageIO;

@Component("cloudflare")
public class CloudflareExtractionProvider implements ExtractionProvider {

    private static final Logger log = LoggerFactory.getLogger(CloudflareExtractionProvider.class);
    private static final String LABEL = "cloudflare";

    /** Longest edge (px) we send to the vision model. Large scans/screenshots are
     *  downscaled to this first: the model reads them fine at this size, huge images
     *  can come back empty, and smaller images cost far fewer credits. */
    private static final int MAX_IMAGE_EDGE = 1600;

    private final CloudflareProperties props;
    private final ObjectMapper mapper;
    private final com.trove.extraction.NeuronRateService neuronRates;
    private final HttpClient http;

    public CloudflareExtractionProvider(CloudflareProperties props, ObjectMapper mapper,
                                        com.trove.extraction.NeuronRateService neuronRates) {
        this.props = props;
        this.mapper = mapper;
        this.neuronRates = neuronRates;
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

        // Downscale big scans/screenshots first: the model reads them fine smaller, huge
        // images can return an empty reply, and it costs far fewer credits.
        byte[] toSend = downscaleIfLarge(fileBytes, mimeType);
        String sendMime = toSend == fileBytes ? mimeType : "image/jpeg";

        // Cloudflare bills EVERY call, including a failed first attempt before a retry, so
        // accumulate usage across attempts and report the total on the winning result.
        long[] tokens = {0};
        double[] credits = {0};
        try {
            return withUsage(callOnce(url, toSend, sendMime, model, false, tokens, credits), tokens[0], credits[0]);
        } catch (ExtractionException e) {
            // The vision model sometimes ignores the JSON instruction (replies in prose) or
            // emits malformed JSON. Retry once with a blunt JSON-only directive — the output
            // is non-deterministic, so a stricter second attempt usually returns clean JSON.
            if (isParseFailure(e)) {
                log.info("Cloudflare returned unparseable output; retrying once with a stricter instruction");
                return withUsage(callOnce(url, toSend, sendMime, model, true, tokens, credits), tokens[0], credits[0]);
            }
            throw e;
        }
    }

    /** True when the model's output couldn't be parsed (no JSON, or malformed JSON) —
     *  retryable via a stricter prompt. */
    private static boolean isParseFailure(ExtractionException e) {
        String m = e.getMessage();
        return m != null && (m.contains("No JSON object found") || m.contains("Could not parse model JSON"));
    }

    /** Records the accumulated token/credit usage on the result so the worker bills it. */
    private ExtractionResult withUsage(ExtractionResult r, long tokens, double credits) {
        if (tokens <= 0) {
            return r;
        }
        java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>(
                r.extra() != null ? r.extra() : java.util.Map.of());
        extra.put("aiTokens", tokens);
        extra.put("aiNeurons", Math.round(credits * 100.0) / 100.0);
        return new ExtractionResult(r.categoryCode(), r.merchantName(), r.docDate(), r.amount(),
                r.currency(), r.dueDate(), r.lineItems(), r.rawText(), extra, r.confidence());
    }

    /**
     * One Cloudflare call + parse. `strict` appends a hardened JSON-only directive. The
     * call's token/credit cost is added to the accumulators BEFORE parsing, so it counts
     * even when this attempt then fails to parse (Cloudflare charged for it regardless).
     */
    private ExtractionResult callOnce(String url, byte[] img, String mime, String model, boolean strict,
                                      long[] tokenAcc, double[] creditAcc) {
        String body = buildRequestBody(img, mime, model, strict);
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

            // Bill this call BEFORE parsing — Cloudflare charged for it whether or not the
            // reply turns out to be valid JSON. Accumulate so a retry adds to the total.
            JsonNode u = result.path("usage");
            long promptTokens = u.path("prompt_tokens").asLong(0);
            long completionTokens = u.path("completion_tokens").asLong(0);
            long totalTokens = u.path("total_tokens").asLong(promptTokens + completionTokens);
            if (totalTokens > 0) {
                tokenAcc[0] += totalTokens;
                creditAcc[0] += neuronRates.neuronsFor(model, promptTokens, completionTokens);
            }

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
            try {
                // Usage is applied by the caller (withUsage) from the accumulators.
                return ExtractionResponseParser.parse(text, mapper);
            } catch (IllegalArgumentException e) {
                // Surface what the model actually said, so a failure like this is diagnosable
                // from the developer trail instead of a bare "No JSON object found".
                throw ExtractionException.transientError(LABEL,
                        e.getMessage() + " — model returned: " + truncate(text), e);
            }
        } catch (ExtractionException e) {
            throw e; // already a well-formed provider error (e.g. the parse-failure above)
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
    /** Appended on a retry to bully a prose-happy model back into raw JSON. */
    private static final String STRICT_JSON =
            "\n\nCRITICAL: Output ONLY the raw JSON object described above. Begin your reply with { and "
            + "end with }. Do NOT use markdown, headings, bold (**), bullet points, or any prose or "
            + "explanation. Return the JSON and nothing else.";

    private String buildRequestBody(byte[] fileBytes, String mimeType, String model, boolean strict) {
        String instruction = strict ? ExtractionPrompt.INSTRUCTION + STRICT_JSON : ExtractionPrompt.INSTRUCTION;
        ObjectNode root = mapper.createObjectNode();
        if (model != null && model.toLowerCase().contains("llava")) {
            ArrayNode image = root.putArray("image");
            for (byte b : fileBytes) {
                image.add(b & 0xFF);
            }
            root.put("prompt", instruction);
            root.put("max_tokens", 2048);
            return root.toString();
        }
        String dataUri = "data:" + imageMime(mimeType) + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(fileBytes);
        ArrayNode messages = root.putArray("messages");
        ArrayNode content = messages.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", instruction);
        content.addObject().put("type", "image_url")
                .putObject("image_url").put("url", dataUri);
        root.put("max_tokens", 2048);
        // Force valid JSON output. Without this the vision model intermittently replies
        // in prose ("No JSON object found"), which sent real receipts to the stub.
        root.putObject("response_format").put("type", "json_object");
        return root.toString();
    }

    /**
     * Downscales an image whose longest edge exceeds MAX_IMAGE_EDGE, re-encoding as JPEG.
     * Returns the ORIGINAL array (same reference) when no resize is needed or on any
     * failure, so the caller can tell whether the mime changed. Never throws.
     */
    private byte[] downscaleIfLarge(byte[] fileBytes, String mimeType) {
        if (mimeType == null || !mimeType.startsWith("image/")) {
            return fileBytes; // non-images (PDF, etc.) are handled elsewhere / by the stub
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(fileBytes));
            if (img == null) {
                return fileBytes; // unreadable format — let the API try the raw bytes
            }
            int longest = Math.max(img.getWidth(), img.getHeight());
            if (longest <= MAX_IMAGE_EDGE) {
                return fileBytes; // already small enough
            }
            double scale = (double) MAX_IMAGE_EDGE / longest;
            int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, w, h, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(scaled, "jpeg", out);
            log.info("Downscaled image {}x{} -> {}x{} for the vision model", img.getWidth(), img.getHeight(), w, h);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Image downscale failed, sending original: {}", e.getMessage());
            return fileBytes;
        }
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
