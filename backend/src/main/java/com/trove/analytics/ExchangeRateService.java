/*
 * ============================================================================
 *  ExchangeRateService — keeps currency conversion rates current
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Documents can be in different currencies (INR, USD, EUR). To total or display
 *  spend in one currency we convert each amount using live rates. Like the neuron
 *  rates, these are fetched from a free source, cached, refreshed on a schedule, and
 *  backed by a sane fallback so the feature always works.
 *
 *  Solution architecture
 *  ---------------------
 *  Fetches USD-based rates (units per 1 USD) from a free, no-key endpoint on startup
 *  and daily. Any-to-any conversion goes via USD: to = amount / rate(from) * rate(to).
 *  Unknown/blank currencies are treated as the default (INR). Never throws.
 *
 *  Reasoning & logic
 *  -----------------
 *  Rates drift daily, so they can't be hardcoded forever; but a wrong-by-a-little rate
 *  only nudges a display total, so best-effort with a fallback is the right posture —
 *  identical to how neuron rates are handled.
 * ============================================================================
 */
package com.trove.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    /** The currencies Trove supports today (kept short on purpose). */
    public static final List<String> SUPPORTED = List.of("INR", "USD", "EUR");
    /** Used when a document has no currency recorded. */
    public static final String DEFAULT = "INR";

    /** Units per 1 USD. Fallback used until the first successful fetch (approximate). */
    private static final Map<String, Double> FALLBACK = Map.of("USD", 1.0, "INR", 83.0, "EUR", 0.92);

    private final ObjectMapper mapper;
    private final String ratesUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private volatile Map<String, Double> rates = new LinkedHashMap<>(FALLBACK);
    private volatile Instant asOf; // null = still on the fallback

    public ExchangeRateService(ObjectMapper mapper,
                               @Value("${trove.fx.rates-url:https://open.er-api.com/v6/latest/USD}") String ratesUrl) {
        this.mapper = mapper;
        this.ratesUrl = ratesUrl;
    }

    /** When the cached rates were last fetched, or null if still using the fallback. */
    public Instant asOf() {
        return asOf;
    }

    /**
     * Converts an amount from one currency to another using the cached rates. Null/blank
     * currencies are treated as the default (INR). Returns the input unchanged if either
     * currency is unknown, so a total never silently becomes zero.
     */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (amount == null) {
            return null;
        }
        String f = norm(from);
        String t = norm(to);
        if (f.equals(t)) {
            return amount;
        }
        Double rf = rates.get(f);
        Double rt = rates.get(t);
        if (rf == null || rt == null || rf == 0) {
            return amount; // unknown pair — leave as-is rather than distort
        }
        return amount.divide(BigDecimal.valueOf(rf), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(rt))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String norm(String c) {
        return (c == null || c.isBlank()) ? DEFAULT : c.trim().toUpperCase();
    }

    /** Refresh on startup (after a short delay) and daily. Failures keep the prior rates. */
    @Scheduled(initialDelay = 15_000, fixedDelay = 24L * 60 * 60 * 1000)
    public void refresh() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ratesUrl))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("FX refresh skipped — rates endpoint HTTP {}", resp.statusCode());
                return;
            }
            JsonNode ratesNode = mapper.readTree(resp.body()).path("rates");
            Map<String, Double> parsed = new LinkedHashMap<>();
            for (String ccy : SUPPORTED) {
                JsonNode r = ratesNode.path(ccy);
                if (r.isNumber() && r.asDouble() > 0) {
                    parsed.put(ccy, r.asDouble());
                }
            }
            if (parsed.containsKey("USD") && parsed.size() >= 2) {
                rates = parsed;
                asOf = Instant.now();
                log.info("Exchange rates refreshed — {}", parsed);
            }
        } catch (Exception e) {
            log.warn("FX refresh failed — keeping existing rates: {}", e.getMessage());
        }
    }
}
