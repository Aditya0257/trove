/*
 * ============================================================================
 *  VaultChatService — "Ask your vault": grounded RAG over the user's documents
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Answers a natural-language question from the caller's own documents: retrieve the
 *  most relevant ones by semantic similarity, then have an LLM write a grounded answer
 *  that cites them.
 *
 *  Business use case
 *  -----------------
 *  "When does my passport expire?", "how much was the last electricity bill?", "find
 *  the fridge warranty" — answered from the vault, with links back to the source.
 *
 *  Solution architecture
 *  ---------------------
 *  Retrieval (EmbeddingService + pgvector) is always scoped to ONE space the caller can
 *  read. The retrieved documents become the ONLY context for the chat model, which is
 *  instructed to cite by number and to refuse when the answer isn't present — so numbers
 *  and dates come from real documents, not hallucination.
 *
 *  Reasoning & logic
 *  -----------------
 *  Cost-safe by construction: the chat call bills through AiUsageTracker (shared 10k/day
 *  + per-user cap). When the budget is spent or the feature is disabled, it DEGRADES to
 *  retrieval-only — it still returns the most relevant documents, just without a written
 *  summary. Context is bounded (topK + snippet cap + max output tokens).
 * ============================================================================
 */
package com.trove.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trove.category.CategoryRepository;
import com.trove.chat.ChatDtos.ChatAnswer;
import com.trove.chat.ChatDtos.Citation;
import com.trove.document.Document;
import com.trove.document.DocumentRepository;
import com.trove.extraction.AiUsageTracker;
import com.trove.extraction.NeuronRateService;
import com.trove.extraction.provider.CloudflareProperties;
import com.trove.merchant.MerchantRepository;
import com.trove.space.SpaceAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class VaultChatService {

    private static final Logger log = LoggerFactory.getLogger(VaultChatService.class);

    private final ChatProperties props;
    private final EmbeddingService embeddings;
    private final SpaceAuthorization authorization;
    private final DocumentRepository documentRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;
    private final AiUsageTracker usage;
    private final NeuronRateService neuronRates;
    private final CloudflareProperties cloudflare;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public VaultChatService(ChatProperties props, EmbeddingService embeddings, SpaceAuthorization authorization,
                            DocumentRepository documentRepository, CategoryRepository categoryRepository,
                            MerchantRepository merchantRepository, AiUsageTracker usage,
                            NeuronRateService neuronRates, CloudflareProperties cloudflare, ObjectMapper mapper) {
        this.props = props;
        this.embeddings = embeddings;
        this.authorization = authorization;
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
        this.usage = usage;
        this.neuronRates = neuronRates;
        this.cloudflare = cloudflare;
        this.mapper = mapper;
    }

    /** Answers {@code question} from documents in {@code spaceId} (caller must be a member). */
    public ChatAnswer ask(UUID spaceId, UUID userId, String question) {
        authorization.requireCanRead(spaceId, userId);
        if (question == null || question.isBlank()) {
            return new ChatAnswer("Ask me something about your documents.", false, List.of());
        }

        // 1) Retrieve the most relevant documents (semantic), scoped to this space.
        List<EmbeddingService.Hit> hits = embeddings.search(spaceId, question, userId, props.getTopK());
        List<Citation> sources = new ArrayList<>();
        int idx = 1;
        for (EmbeddingService.Hit hit : hits) {
            Document doc = documentRepository.findById(hit.documentId()).orElse(null);
            if (doc != null) {
                sources.add(toCitation(doc, idx++));
            }
        }
        if (sources.isEmpty()) {
            return new ChatAnswer(
                    "I couldn't find anything about that in this space. Try different words, "
                    + "or upload the document first.", false, List.of());
        }

        // 2) Grounded answer — unless the AI budget is spent or the feature is off, in which
        //    case degrade to retrieval-only (still useful: the ranked source documents).
        String block = usage.blockReason(userId);
        if (!props.isEnabled() || block != null) {
            String why = !props.isEnabled() ? "The assistant is turned off"
                    : "Today's AI budget is used up";
            return new ChatAnswer(why + ", so here are the documents most related to your question.",
                    false, sources);
        }
        try {
            String answer = callChat(buildPrompt(question, sources), userId);
            return new ChatAnswer(answer.isBlank() ? "I couldn't compose an answer, but see the sources below."
                    : answer, true, sources);
        } catch (Exception e) {
            log.warn("Vault chat answer failed ('{}') — returning sources only: {}", question, e.getMessage());
            return new ChatAnswer("I hit a problem writing the answer, but here are the most relevant documents.",
                    false, sources);
        }
    }

    private Citation toCitation(Document doc, int index) {
        String category = doc.getCategoryId() == null ? null
                : categoryRepository.findById(doc.getCategoryId()).map(c -> c.getLabel()).orElse(null);
        String merchant = doc.getMerchantId() == null ? null
                : merchantRepository.findById(doc.getMerchantId()).map(m -> m.getCanonicalName()).orElse(null);
        String title = merchant != null ? merchant
                : (doc.getOriginalFilename() != null ? doc.getOriginalFilename() : "Document");
        String snippet = doc.getRawText() == null ? "" : doc.getRawText().trim();
        if (snippet.length() > props.getMaxSnippetChars()) {
            snippet = snippet.substring(0, props.getMaxSnippetChars()) + "…";
        }
        return new Citation(doc.getId().toString(), index, title, category,
                doc.getDocDate(), doc.getAmount(), doc.getCurrency(), snippet);
    }

    private String buildPrompt(String question, List<Citation> sources) {
        StringBuilder ctx = new StringBuilder();
        for (Citation c : sources) {
            ctx.append('[').append(c.index()).append("] ");
            if (c.category() != null) ctx.append("Category: ").append(c.category()).append(" | ");
            if (c.title() != null) ctx.append("Merchant/File: ").append(c.title()).append(" | ");
            if (c.docDate() != null) ctx.append("Date: ").append(c.docDate()).append(" | ");
            if (c.amount() != null) {
                ctx.append("Amount: ").append(c.amount());
                if (c.currency() != null) ctx.append(' ').append(c.currency());
            }
            ctx.append('\n').append(c.snippet()).append("\n\n");
        }
        return """
                You are Trove's assistant. Answer the user's question using ONLY the documents
                below. Cite the documents you use by their number, like [1] or [2]. If the
                documents do not contain the answer, say you couldn't find it — never invent a
                merchant, amount, date, or fact. Be concise and specific.

                Documents:
                %s
                Question: %s
                Answer:""".formatted(ctx.toString().trim(), question.trim());
    }

    private String callChat(String prompt, UUID userId) throws Exception {
        String model = props.getChatModel();
        String url = "https://api.cloudflare.com/client/v4/accounts/%s/ai/run/%s"
                .formatted(cloudflare.getAccountId(), model);
        var root = mapper.createObjectNode();
        root.put("temperature", 0.2);
        root.put("max_tokens", 300);           // bound output cost
        var messages = root.putArray("messages");
        messages.addObject().put("role", "user").put("content", prompt);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + cloudflare.getApiToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(resp.body());
        JsonNode u = json.path("result").path("usage");
        long inTok = u.path("prompt_tokens").asLong(0);
        long outTok = u.path("completion_tokens").asLong(0);
        if (inTok + outTok > 0) {
            usage.record(userId, neuronRates.neuronsFor(model, inTok, outTok), inTok + outTok);
        }
        JsonNode response = json.path("result").path("response");
        return response.isTextual() ? response.asText("") : response.toString();
    }
}
