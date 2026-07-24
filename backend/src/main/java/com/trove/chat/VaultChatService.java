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

import com.trove.category.Category;
import com.trove.category.CategoryRepository;
import com.trove.chat.ChatDtos.ChatAnswer;
import com.trove.chat.ChatDtos.Citation;
import com.trove.document.Document;
import com.trove.document.DocumentRepository;
import com.trove.merchant.Merchant;
import com.trove.extraction.AiUsageTracker;
import com.trove.merchant.MerchantRepository;
import com.trove.space.SpaceAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private final ModelRouter router;
    private final CloudflareChatClient chatClient;

    public VaultChatService(ChatProperties props, EmbeddingService embeddings, SpaceAuthorization authorization,
                            DocumentRepository documentRepository, CategoryRepository categoryRepository,
                            MerchantRepository merchantRepository, AiUsageTracker usage,
                            ModelRouter router, CloudflareChatClient chatClient) {
        this.props = props;
        this.embeddings = embeddings;
        this.authorization = authorization;
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
        this.usage = usage;
        this.router = router;
        this.chatClient = chatClient;
    }

    /** Answers {@code question} from documents in {@code spaceId} (caller must be a member). */
    public ChatAnswer ask(UUID spaceId, UUID userId, String question) {
        authorization.requireCanRead(spaceId, userId);
        if (question == null || question.isBlank()) {
            return new ChatAnswer("Ask me something about your documents.", false, List.of());
        }

        // 1) Retrieve the most relevant documents (semantic), scoped to this space. Build the
        //    citations (for the UI) and a type-aware context block (for the model) together.
        List<EmbeddingService.Hit> hits = embeddings.search(spaceId, question, userId, props.getTopK());
        List<Citation> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        int idx = 1;
        for (EmbeddingService.Hit hit : hits) {
            Document doc = documentRepository.findById(hit.documentId()).orElse(null);
            if (doc == null) {
                continue;
            }
            Category cat = doc.getCategoryId() == null ? null
                    : categoryRepository.findById(doc.getCategoryId()).orElse(null);
            String code = cat == null ? null : cat.getCode();
            String label = cat == null ? null : cat.getLabel();
            String merchant = doc.getMerchantId() == null ? null
                    : merchantRepository.findById(doc.getMerchantId()).map(Merchant::getCanonicalName).orElse(null);
            boolean isEmail = "email".equals(code);
            sources.add(citation(doc, idx, label, merchant, isEmail));
            context.append(contextBlock(doc, idx, label, merchant, isEmail));
            idx++;
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
            ModelRouter.Decision route = router.pick(question, userId);
            log.info("Vault chat routed to {} ({}: {})", route.model(), route.tier(), route.reason());
            String answer = chatClient.chat(route.model(), buildPrompt(question, context.toString()),
                    300, 0.2, props.getTimeoutSeconds(), userId);
            // The small model occasionally degenerates to just a citation marker ("[1]") or
            // whitespace. Never surface that — fall back to a plain summary of the top match.
            if (isEmptyAnswer(answer)) {
                answer = fallbackAnswer(sources);
            }
            return new ChatAnswer(answer, true, sources);
        } catch (Exception e) {
            log.warn("Vault chat answer failed ('{}') - returning sources only: {}", question, e.getMessage());
            return new ChatAnswer("I hit a problem writing the answer, but here are the most relevant documents.",
                    false, sources);
        }
    }

    /** Builds the UI citation. Emails are titled by subject/topic and carry no amount. */
    private Citation citation(Document doc, int index, String label, String merchant, boolean isEmail) {
        String title = isEmail
                ? firstNonBlank(extraStr(doc, "mailSubject"), extraStr(doc, "mailTopic"),
                        doc.getOriginalFilename(), "Email")
                : (merchant != null ? merchant
                        : (doc.getOriginalFilename() != null ? doc.getOriginalFilename() : "Document"));
        String snippet = doc.getRawText() == null ? "" : doc.getRawText().trim();
        if (snippet.length() > props.getMaxSnippetChars()) {
            snippet = snippet.substring(0, props.getMaxSnippetChars()) + "…";
        }
        return new Citation(doc.getId().toString(), index, title, label,
                doc.getDocDate(), isEmail ? null : doc.getAmount(), isEmail ? null : doc.getCurrency(), snippet);
    }

    /** One document's block for the model, written by its KIND so the model reads it right:
     *  emails expose Subject/Sender/Account (never an amount); bills expose Merchant/Amount. */
    private String contextBlock(Document doc, int index, String label, String merchant, boolean isEmail) {
        StringBuilder b = new StringBuilder();
        b.append('[').append(index).append("] ").append(label == null ? "Document" : label);
        if (isEmail) {
            field(b, "Subject", extraStr(doc, "mailSubject"));
            field(b, "Sender/Topic", extraStr(doc, "mailTopic"));
            field(b, "Account", extraStr(doc, "mailAccount"));
            field(b, "Address", extraStr(doc, "mailAddress"));
            field(b, "Date", str(doc.getDocDate()));
        } else {
            field(b, "Merchant", merchant);
            field(b, "Date", str(doc.getDocDate()));
            if (doc.getAmount() != null) {
                field(b, "Amount", doc.getAmount() + (doc.getCurrency() != null ? " " + doc.getCurrency() : ""));
            }
            field(b, "Due/Expiry", str(doc.getDueDate()));
        }
        field(b, "Notes", extraStr(doc, "notes"));
        b.append('\n');
        String snippet = doc.getRawText() == null ? "" : doc.getRawText().trim();
        if (snippet.length() > props.getMaxSnippetChars()) {
            snippet = snippet.substring(0, props.getMaxSnippetChars());
        }
        if (!snippet.isBlank()) {
            b.append(snippet).append('\n');
        }
        return b.append('\n').toString();
    }

    private String buildPrompt(String question, String context) {
        return """
                You are Trove's assistant for a personal document vault. Answer the user's
                question using ONLY the documents below, and cite the ones you use by number
                like [1] or [2].

                Documents come in different KINDS - read each by its fields:
                - Bills / receipts / purchases: have Merchant, Date and Amount (money spent).
                - Emails (category Email): have Subject, Sender/Topic, Account and Date. They are
                  saved notes, NOT spending - an email's numbers are never an amount paid.
                - IDs, policies, warranties, subscriptions: may have a Due/Expiry date.

                Do:
                - Use Date for "last / latest / most recent" (newest) and "first / oldest" (earliest).
                - Add Amounts for totals or "how much" - bills only, never emails.
                - Use Subject / Sender/Topic / Account to answer questions about emails.
                - Use the Due/Expiry date for "expires", "renews", "due".
                - Honour exclusions: for "not X", "except X", "other than X", leave those documents out.
                - For "list / which / all", list every document that matches.
                - Answer whenever ANY document is relevant, even if the wording differs.

                Do NOT:
                - Invent a merchant, amount, date, subject, or fact that is not shown.
                - Treat an email (or a number inside one) as spending.
                - Say you couldn't find it while a relevant document is present; only refuse when
                  NONE of the documents relate to the question.
                - Answer with only a citation like "[1]". A citation SUPPORTS your sentence; it is
                  never the whole answer.

                Always write a full sentence that states the actual value in words, then cite it.
                Example - if [1] is an electricity bill for 27.50 USD dated 2026-07-16, answer:
                "Your last electricity bill was 27.50 USD on 2026-07-16 [1]."

                Documents:
                %s
                Question: %s
                Answer:""".formatted(context.trim(), question.trim());
    }

    // ── small helpers ────────────────────────────────────────────────────────
    private void field(StringBuilder b, String label, String value) {
        if (value != null && !value.isBlank()) {
            b.append(" | ").append(label).append(": ").append(value);
        }
    }

    private String extraStr(Document doc, String key) {
        var extra = doc.getExtra();
        if (extra == null) {
            return null;
        }
        Object v = extra.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** True when the model gave nothing usable — blank, or only citation markers/punctuation. */
    private boolean isEmptyAnswer(String answer) {
        if (answer == null) {
            return true;
        }
        String stripped = answer.replaceAll("\\[\\d+\\]", "").replaceAll("[\\s\\p{Punct}]", "");
        return stripped.isBlank();
    }

    /** Plain summary of the top match, used when the model fails to write a sentence. */
    private String fallbackAnswer(List<Citation> sources) {
        Citation top = sources.get(0);
        StringBuilder b = new StringBuilder("The most relevant document is ").append(top.title());
        if (top.docDate() != null) {
            b.append(" from ").append(top.docDate());
        }
        if (top.amount() != null) {
            b.append(", ").append(top.amount());
            if (top.currency() != null) {
                b.append(' ').append(top.currency());
            }
        }
        return b.append(" [1]. Ask more specifically if that's not the one.").toString();
    }

}
