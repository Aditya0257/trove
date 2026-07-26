/*
 * ============================================================================
 *  LlmQueryParser — turns a natural-language query into filters using an LLM
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Asks a TEXT LLM to convert a free-form search ("top 10 expensive shopping bills")
 *  into a structured SearchQuery (category, sort-by-amount, limit, amount/date range).
 *
 *  Business use case
 *  -----------------
 *  Real natural-language search — understanding intent the rule-based parser can't
 *  (superlatives, sorting, counts). Returns empty on any problem so SearchService
 *  falls back to the rule-based parser (never worse than before).
 *
 *  Solution architecture
 *  ---------------------
 *  Provider-agnostic: 'ollama' (local, e.g. llama3 — no vision needed) or 'cloudflare'
 *  (hosted Workers AI text model). Same swappable philosophy as extraction (D9). Only
 *  active when trove.search.llm.enabled.
 *
 *  Reasoning & logic
 *  -----------------
 *  A tightly-scoped prompt asks for JSON only; the shared tolerant JSON extraction is
 *  reused. Unknown/blank fields are simply left unset on the SearchQuery.
 * ============================================================================
 */
package com.trove.integration;
import com.trove.config.SearchProperties;
import com.trove.dto.SearchQuery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trove.config.CloudflareProperties;
import com.trove.config.OllamaProperties;
import com.trove.integration.ExtractionPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LlmQueryParser {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryParser.class);

    /** Valid category codes; the LLM sometimes invents one (e.g. "bill"), which we drop. */
    private static final Set<String> VALID_CATEGORIES =
            Arrays.stream(ExtractionPrompt.SEARCH_CATEGORY_CODES.split(",\\s*"))
                    .map(String::trim).collect(Collectors.toUnmodifiableSet());

    private final SearchProperties props;
    private final OllamaProperties ollama;
    private final CloudflareProperties cloudflare;
    private final ObjectMapper mapper;
    private final com.trove.service.impl.AiUsageTracker usage;
    private final com.trove.service.impl.NeuronRateService neuronRates;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public LlmQueryParser(SearchProperties props, OllamaProperties ollama,
                          CloudflareProperties cloudflare, ObjectMapper mapper,
                          com.trove.service.impl.AiUsageTracker usage,
                          com.trove.service.impl.NeuronRateService neuronRates) {
        this.props = props;
        this.ollama = ollama;
        this.cloudflare = cloudflare;
        this.mapper = mapper;
        this.usage = usage;
        this.neuronRates = neuronRates;
    }

    /** Parses the query with the configured LLM, or empty to fall back to rules.
     *  {@code userId} is billed for any AI tokens/neurons the parse consumes. */
    public Optional<SearchQuery> parse(String text, java.util.UUID userId) {
        if (!props.getLlm().isEnabled() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        // Respect the daily AI budget: when the shared or per-user allowance is spent,
        // skip the LLM and let SearchService fall back to the free rule-based parser.
        String block = usage.blockReason(userId);
        if (block != null) {
            log.info("Search LLM skipped - {} - falling back to rules", block);
            return Optional.empty();
        }
        try {
            String json = "cloudflare".equalsIgnoreCase(props.getLlm().getProvider())
                    ? callCloudflare(buildPrompt(text), userId)
                    : callOllama(buildPrompt(text));
            return Optional.of(toQuery(extractJson(json)));
        } catch (Exception e) {
            log.warn("LLM query parse failed ('{}') - falling back to rules: {}", text, e.getMessage());
            return Optional.empty();
        }
    }

    private String buildPrompt(String text) {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        return """
                Today is %s. Convert the user's document-search request into a JSON object.
                Include ONLY fields the request clearly implies; OMIT everything else.
                Fields:
                  categoryCode: one of [%s]  (omit if none clearly apply)
                  text: a merchant/brand/product keyword only (e.g. "Nike"). NEVER put
                        filler words like "give", "this", "space", "bills", "of". Omit if none.
                  amountMin, amountMax: numbers
                  dateFrom, dateTo: "YYYY-MM-DD"
                  sortBy: "amount" or "date";  sortDir: "asc" or "desc";  limit: integer
                Date rules: a bare month name uses the CURRENT year (%d). Only set a
                different year if the request states one. If no time is mentioned, OMIT
                dateFrom/dateTo. NEVER guess a year.
                Intent rules: "expensive"/"highest"/"most"/"top" => sortBy=amount, sortDir=desc.
                "top N"/"last N" => limit=N. "last"/"latest"/"most recent" => sortBy=date,
                sortDir=desc, limit=1.

                Examples:
                  "my last water bill" => {"categoryCode":"water","sortBy":"date","sortDir":"desc","limit":1}
                  "top 10 expensive shopping bills" => {"categoryCode":"shopping","sortBy":"amount","sortDir":"desc","limit":10}
                  "electricity from july" => {"categoryCode":"electricity","dateFrom":"%d-07-01","dateTo":"%d-07-31"}
                  "all Nike purchases" => {"categoryCode":"shopping","text":"Nike"}
                  "get all my emails" => {"categoryCode":"email"}

                Respond with ONLY the JSON object, no prose.
                Request: %s
                """.formatted(today, ExtractionPrompt.SEARCH_CATEGORY_CODES, year, year, year, text);
    }

    private String callOllama(String prompt) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
        root.put("model", props.getLlm().getModel());
        root.put("prompt", prompt);
        root.put("stream", false);
        root.put("format", "json");
        root.putObject("options").put("temperature", 0);   // deterministic parsing
        String body = root.toString();
        HttpRequest req = HttpRequest.newBuilder(URI.create(ollama.getEndpoint() + "/api/generate"))
                .timeout(Duration.ofSeconds(props.getLlm().getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body()).path("response").asText("");
    }

    private String callCloudflare(String prompt, java.util.UUID userId) throws Exception {
        String model = props.getLlm().getModel();
        String url = "https://api.cloudflare.com/client/v4/accounts/%s/ai/run/%s"
                .formatted(cloudflare.getAccountId(), model);
        var root = mapper.createObjectNode();
        root.put("temperature", 0);   // deterministic parsing
        var messages = root.putArray("messages");
        messages.addObject().put("role", "user").put("content", prompt);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.getLlm().getTimeoutSeconds()))
                .header("Authorization", "Bearer " + cloudflare.getApiToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode respJson = mapper.readTree(resp.body());
        JsonNode u = respJson.path("result").path("usage");
        long promptTokens = u.path("prompt_tokens").asLong(0);
        long completionTokens = u.path("completion_tokens").asLong(0);
        long totalTokens = u.path("total_tokens").asLong(promptTokens + completionTokens);
        if (totalTokens > 0) {
            // search LLM also draws on the shared daily allowance — bill it to the user
            usage.record(userId, neuronRates.neuronsFor(model, promptTokens, completionTokens), totalTokens);
        }
        // Workers AI instruct models often return result.response as a JSON OBJECT
        // (structured output), not a string; .asText() would be "". Re-serialize the
        // object so extractJson() sees real JSON; otherwise take the plain-text reply.
        JsonNode response = respJson.path("result").path("response");
        return (response.isObject() || response.isArray()) ? response.toString() : response.asText("");
    }

    private JsonNode extractJson(String raw) throws Exception {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON in LLM response");
        }
        return mapper.readTree(raw.substring(start, end + 1));
    }

    private SearchQuery toQuery(JsonNode n) {
        SearchQuery q = new SearchQuery();
        // Only accept a real category code; ignore hallucinations like "bill" so the
        // query isn't filtered to a non-existent category (which would match nothing).
        if (has(n, "categoryCode") && VALID_CATEGORIES.contains(n.get("categoryCode").asText())) {
            q.setCategoryCode(n.get("categoryCode").asText());
        }
        if (has(n, "text")) q.setText(n.get("text").asText());
        if (has(n, "amountMin")) q.setAmountMin(new BigDecimal(n.get("amountMin").asText()));
        if (has(n, "amountMax")) q.setAmountMax(new BigDecimal(n.get("amountMax").asText()));
        if (has(n, "dateFrom")) q.setDateFrom(parseDate(n.get("dateFrom").asText()));
        if (has(n, "dateTo")) q.setDateTo(parseDate(n.get("dateTo").asText()));
        if (has(n, "sortBy")) q.setSortBy(n.get("sortBy").asText());
        if (has(n, "sortDir")) q.setSortDir(n.get("sortDir").asText());
        q.setLimit(has(n, "limit") ? n.get("limit").asInt() : 50);
        return q;
    }

    private boolean has(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && !v.isNull() && !v.asText().isBlank();
    }

    private LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
