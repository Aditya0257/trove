/*
 * ============================================================================
 *  NeuronRateService — keeps the token→neuron conversion rates current
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Cloudflare Workers AI returns TOKENS per call, but the free-tier limit is
 *  denominated in NEURONS. To show usage against the 10,000/day allowance we convert
 *  tokens→neurons using each model's published rate. Those rates can change when
 *  Cloudflare re-prices, so this service refreshes them from Cloudflare's own models
 *  API instead of trusting a hardcoded table forever.
 *
 *  Solution architecture
 *  ---------------------
 *  Cloudflare's models API exposes price in USD per million tokens; neurons have a
 *  fixed price ($0.011 per 1,000 neurons), so neurons/M = USD-per-M ÷ USD-per-neuron.
 *  We fetch the catalogue on startup and weekly, cache a model→[inNeuronsPerM,
 *  outNeuronsPerM] map, and fall back to the last-known-good published rates (baked in
 *  at build time) whenever the fetch fails or a model isn't priced. It never throws and
 *  never blocks a request — worst case we keep using the previous rates.
 *
 *  Reasoning & logic
 *  -----------------
 *  The neuron count is a display estimate; Cloudflare's hard limit is the real ceiling.
 *  So "best-effort, always falls back" is the right posture: staleness only nudges the
 *  gauge, never correctness. Uses the Workers AI token (models API needs no extra scope).
 * ============================================================================
 */
package com.trove.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trove.extraction.provider.CloudflareProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class NeuronRateService {

    private static final Logger log = LoggerFactory.getLogger(NeuronRateService.class);

    // Last-known-good published rates (neurons per million tokens), used as a fallback
    // when the live fetch fails or a model isn't priced. Keyed by a model-name substring.
    // Ordered so more specific keys are tried first.
    private static final Map<String, double[]> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("llama-3.2-11b-vision", new double[]{4_410, 61_493});
        DEFAULTS.put("llama-3.1-8b", new double[]{25_608, 75_147});
    }
    /** Conservative catch-all for an unmapped, unpriced model. */
    private static final double[] GENERIC_FALLBACK = {10_000, 60_000};

    private final CloudflareProperties cf;
    private final ObjectMapper mapper;
    private final double usdPerNeuron;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** model name (lower-case) -> [inputNeuronsPerM, outputNeuronsPerM], refreshed live. */
    private volatile Map<String, double[]> live = Map.of();

    public NeuronRateService(CloudflareProperties cf, ObjectMapper mapper,
                             @Value("${trove.ai.neuron-usd-per-thousand:0.011}") double neuronUsdPerThousand) {
        this.cf = cf;
        this.mapper = mapper;
        this.usdPerNeuron = neuronUsdPerThousand / 1000.0;
    }

    /** Neurons billed for one call, from its real token counts and the freshest rates. */
    public double neuronsFor(String model, long promptTokens, long completionTokens) {
        double[] r = ratesFor(model);
        return promptTokens / 1_000_000.0 * r[0] + completionTokens / 1_000_000.0 * r[1];
    }

    /** Freshest rate for a model: exact live match → live substring → baked-in default. */
    private double[] ratesFor(String model) {
        String m = model == null ? "" : model.toLowerCase();
        Map<String, double[]> snapshot = live; // volatile read once
        double[] exact = snapshot.get(m);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, double[]> e : snapshot.entrySet()) {
            if (m.contains(e.getKey()) || e.getKey().contains(m)) {
                return e.getValue();
            }
        }
        for (Map.Entry<String, double[]> e : DEFAULTS.entrySet()) {
            if (m.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return GENERIC_FALLBACK;
    }

    /**
     * Refresh from Cloudflare on startup (after a short delay) and then weekly. On any
     * failure we log and keep the rates we already had — this never disrupts requests.
     */
    @Scheduled(initialDelay = 20_000, fixedDelay = 7L * 24 * 60 * 60 * 1000)
    public void refresh() {
        if (cf.getAccountId().isBlank() || cf.getApiToken().isBlank()) {
            return; // Cloudflare not configured — nothing to fetch; defaults apply.
        }
        try {
            String url = "https://api.cloudflare.com/client/v4/accounts/%s/ai/models/search?per_page=200"
                    .formatted(cf.getAccountId());
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + cf.getApiToken())
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("Neuron rate refresh skipped — Cloudflare models API HTTP {}", resp.statusCode());
                return;
            }
            Map<String, double[]> parsed = parse(mapper.readTree(resp.body()).path("result"));
            if (!parsed.isEmpty()) {
                live = parsed;
                log.info("Neuron rates refreshed from Cloudflare — {} models priced", parsed.size());
            }
        } catch (Exception e) {
            log.warn("Neuron rate refresh failed — keeping existing rates: {}", e.getMessage());
        }
    }

    /** Builds model→neuron-rate from the models API, converting its USD/M-token price. */
    private Map<String, double[]> parse(JsonNode result) {
        Map<String, double[]> parsed = new HashMap<>();
        for (JsonNode model : result) {
            String name = model.path("name").asText("").toLowerCase();
            if (name.isBlank()) {
                continue;
            }
            Double in = null;
            Double out = null;
            for (JsonNode prop : model.path("properties")) {
                if (!"price".equals(prop.path("property_id").asText())) {
                    continue;
                }
                for (JsonNode p : prop.path("value")) {
                    String unit = p.path("unit").asText("");
                    double usdPerMillion = p.path("price").asDouble(0);
                    if (usdPerMillion <= 0) {
                        continue;
                    }
                    if (unit.contains("input")) {
                        in = usdPerMillion / usdPerNeuron;
                    } else if (unit.contains("output")) {
                        out = usdPerMillion / usdPerNeuron;
                    }
                }
            }
            if (in != null && out != null) {
                parsed.put(name, new double[]{in, out});
            }
        }
        return parsed;
    }
}
