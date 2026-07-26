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
package com.trove.service.impl;
import com.trove.config.ChatProperties;
import com.trove.integration.CloudflareChatClient;
import com.trove.integration.ModelRouter;

import com.trove.entity.Category;
import com.trove.repository.CategoryRepository;
import com.trove.dto.ChatDtos.ChatAnswer;
import com.trove.dto.ChatDtos.Citation;
import com.trove.entity.Document;
import com.trove.repository.DocumentRepository;
import com.trove.entity.Merchant;
import com.trove.service.impl.AiUsageTracker;
import com.trove.repository.MerchantRepository;
import com.trove.entity.Reminder;
import com.trove.repository.ReminderRepository;
import com.trove.enums.ReminderStatus;
import com.trove.enums.ReminderType;
import com.trove.security.SpaceAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final ReminderRepository reminderRepository;
    private final AiUsageTracker usage;
    private final ModelRouter router;
    private final CloudflareChatClient chatClient;
    private final QueryNormalizer queryNormalizer;

    public VaultChatService(ChatProperties props, EmbeddingService embeddings, SpaceAuthorization authorization,
                            DocumentRepository documentRepository, CategoryRepository categoryRepository,
                            MerchantRepository merchantRepository, ReminderRepository reminderRepository,
                            AiUsageTracker usage, ModelRouter router, CloudflareChatClient chatClient,
                            QueryNormalizer queryNormalizer) {
        this.props = props;
        this.embeddings = embeddings;
        this.authorization = authorization;
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
        this.reminderRepository = reminderRepository;
        this.usage = usage;
        this.router = router;
        this.chatClient = chatClient;
        this.queryNormalizer = queryNormalizer;
    }

    /** Answers {@code question} from documents in {@code spaceId} (caller must be a member). */
    public ChatAnswer ask(UUID spaceId, UUID userId, String question) {
        authorization.requireCanRead(spaceId, userId);
        if (question == null || question.isBlank()) {
            return new ChatAnswer("Ask me something about your documents.", false, List.of());
        }

        // 0) Normalize to an English search query. Documents here are English (Indian bills), and
        //    the embedder is English-only, so a Hindi/Hinglish question would not match anything.
        //    Retrieval and reminder-detection use this English query; the original question still
        //    drives the answer below, so the assistant can reply in the user's own language.
        String searchQuery = queryNormalizer.toSearchQuery(question, userId);

        // 1) Retrieve the most relevant documents (semantic), scoped to this space. Then apply
        //    the relevance floor: search always returns the topK nearest documents however far
        //    away, so drop clearly-unrelated ones - otherwise an off-topic question surfaces weak
        //    "sources" beneath a refusal, which reads as broken.
        List<EmbeddingService.Hit> raw = embeddings.search(spaceId, searchQuery, userId, props.getTopK());
        if (log.isDebugEnabled()) {
            log.debug("Vault chat '{}' (search: '{}') distances: {}", question, searchQuery,
                    raw.stream().map(h -> String.format("%.3f", h.distance())).toList());
        }
        List<EmbeddingService.Hit> hits = raw.stream()
                .filter(h -> h.distance() <= props.getMaxDistance())
                .toList();
        // Build the citations (for the UI) and a type-aware context block (for the model) together.
        List<Citation> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        Map<UUID, Integer> docIndex = new HashMap<>();  // documentId -> citation number (dedupe + reminder refs)
        int[] nextIdx = {1};
        for (EmbeddingService.Hit hit : hits) {
            Document doc = documentRepository.findById(hit.documentId()).orElse(null);
            if (doc == null || docIndex.containsKey(doc.getId())) {
                continue;
            }
            docIndex.put(doc.getId(), addDocCitation(doc, sources, context, nextIdx));
        }

        // Reminders are a separate KIND of vault data and are NOT embedded, so a question about
        // "reminders / renewals / what's due / warranties" would otherwise retrieve nothing. When
        // the question is about them, fold the space's reminders into the context - pulling each
        // reminder's linked document in as a citation - so the assistant can actually answer.
        String reminderCtx = isReminderQuestion(searchQuery)
                ? reminderContext(spaceId, sources, context, docIndex, nextIdx) : "";

        if (sources.isEmpty() && reminderCtx.isBlank()) {
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
            // Answer from the normalized (English) query too: the English-only context and the
            // small model give a far more reliable, consistent answer than mixing a Hinglish
            // question with English sources (which can flip into a contradictory refusal).
            ModelRouter.Decision route = router.pick(searchQuery, userId);
            log.info("Vault chat routed to {} ({}: {})", route.model(), route.tier(), route.reason());
            String answer = chatClient.chat(route.model(), buildPrompt(searchQuery, context + reminderCtx),
                    300, 0.2, props.getTimeoutSeconds(), userId);
            // The small model occasionally degenerates to just a citation marker ("[1]") or
            // whitespace. Never surface that — fall back to a plain summary of the top match.
            if (isEmptyAnswer(answer)) {
                answer = fallbackAnswer(sources);
            }
            // Relevance by the model's own judgement: if the grounded answer cites NO document,
            // none of the retrieved ones supported an answer (a refusal), so don't dangle
            // unrelated "sources" beneath it - that mismatch is exactly what reads as broken.
            List<Citation> shown = hasCitation(answer) ? sources : List.of();
            return new ChatAnswer(answer, true, shown);
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

    /** Adds one document as citation [n] + context block, returning its number. */
    private int addDocCitation(Document doc, List<Citation> sources, StringBuilder context, int[] nextIdx) {
        int i = nextIdx[0]++;
        Category cat = doc.getCategoryId() == null ? null
                : categoryRepository.findById(doc.getCategoryId()).orElse(null);
        String code = cat == null ? null : cat.getCode();
        String label = cat == null ? null : cat.getLabel();
        String merchant = doc.getMerchantId() == null ? null
                : merchantRepository.findById(doc.getMerchantId()).map(Merchant::getCanonicalName).orElse(null);
        boolean isEmail = "email".equals(code);
        sources.add(citation(doc, i, label, merchant, isEmail));
        context.append(contextBlock(doc, i, label, merchant, isEmail));
        return i;
    }

    /** Keywords that signal the question is about reminders / due dates / renewals / warranties. */
    private static final List<String> REMINDER_HINTS = List.of(
            "remind", "reminder", "due", "overdue", "renew", "renewal", "expire", "expir",
            "warranty", "warranties", "upcoming", "coming up", "deadline", "when is");

    private boolean isReminderQuestion(String question) {
        String q = question.toLowerCase();
        return REMINDER_HINTS.stream().anyMatch(q::contains);
    }

    /**
     * Appends a "Reminders set in this space" section to {@code context} and returns it as a
     * standalone string (so callers can detect whether anything was added). Each reminder shows
     * its kind + date and, when it is tied to a document, a [n] reference to that document -
     * adding the document as a fresh citation if the semantic search didn't already surface it.
     * Dismissed reminders are skipped; the rest are listed oldest-first by their remind date.
     */
    private String reminderContext(UUID spaceId, List<Citation> sources, StringBuilder context,
                                   Map<UUID, Integer> docIndex, int[] nextIdx) {
        List<Reminder> reminders = reminderRepository.findBySpaceIdOrderByRemindOnAsc(spaceId).stream()
                .filter(r -> !ReminderStatus.DISMISSED.equals(r.getStatus()))
                .toList();
        if (reminders.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("\nReminders set in this space (scheduled nudges - use these for "
                + "reminder / due / renewal / warranty questions):\n");
        for (Reminder r : reminders) {
            Integer ref = null;
            if (r.getDocumentId() != null) {
                ref = docIndex.get(r.getDocumentId());
                if (ref == null) {
                    Document doc = documentRepository.findById(r.getDocumentId()).orElse(null);
                    if (doc != null) {
                        ref = addDocCitation(doc, sources, context, nextIdx);
                        docIndex.put(doc.getId(), ref);
                    }
                }
            }
            b.append("- ").append(humanType(r.getType())).append(" on ").append(r.getRemindOn());
            if (ref != null) {
                // Give a SHORT document name (the citation title: merchant/subject/filename),
                // so the model names the document concisely instead of pasting its raw text.
                b.append(" for ").append(sources.get(ref - 1).title()).append(" [").append(ref).append(']');
            }
            b.append('\n');
        }
        context.append(b);
        return b.toString();
    }

    /** Reader-friendly label for a reminder type ('warranty_expiry' -> 'Warranty expiry'). */
    private String humanType(String type) {
        return switch (type) {
            case ReminderType.DUE -> "Payment due";
            case ReminderType.RENEWAL -> "Renewal";
            case ReminderType.WARRANTY_EXPIRY -> "Warranty expiry";
            default -> type == null ? "Reminder" : type.replace('_', ' ');
        };
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
                A "Reminders set in this space" section may also appear: these are scheduled nudges
                (payment due / renewal / warranty expiry), each with a date and, where known, a [n]
                reference to the document it is about.

                Do:
                - Use Date for "last / latest / most recent" (newest) and "first / oldest" (earliest).
                - Add Amounts for totals or "how much" - bills only, never emails.
                - Use Subject / Sender/Topic / Account to answer questions about emails.
                - Use the Due/Expiry date for "expires", "renews", "due".
                - For questions about reminders, due dates, renewals or warranties, use the Reminders
                  section: list each nudge with its date and what it is for, citing the document [n].
                  Name the document briefly (the short name given in the Reminders line); never paste
                  a document's raw text into the answer.
                - Honour exclusions: for "not X", "except X", "other than X", leave those documents out.
                - For "list / which / all", list every document that matches.
                - Answer whenever ANY document is relevant, even if the wording differs.

                Do NOT:
                - Invent a merchant, amount, date, subject, or fact that is not shown.
                - Treat an email (or a number inside one) as spending.
                - Say you couldn't find it while a relevant document is present; only refuse when
                  NONE of the documents relate to the question.
                - Answer with only a citation. A citation SUPPORTS your words; it is never the answer.
                - Use markdown: no #, no *, no bold, no bullet dashes.

                Formatting:
                - Write a natural, talkable answer that states the actual values in words.
                - If it is a SINGLE fact, reply in ONE plain sentence.
                - If it lists SEVERAL items, put each on its own line numbered "1.) ", "2.) ", "3.) ".
                - After each fact, add its source as [n] using the document numbers below. These are
                  only for linking and are hidden from the reader, so keep them at the end of the
                  sentence or line, never as the whole reply.

                Examples:
                Single -> "Your last electricity bill was 27.50 USD on 2026-07-16 [1]."
                List ->
                1.) Reliance Fresh, 735.00 INR on 2026-07-12 [1]
                2.) Big Bazaar, 250.00 INR on 2026-07-13 [2]

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

    /** True when the answer references at least one document by a [n] citation marker. */
    private boolean hasCitation(String answer) {
        return answer != null && answer.matches("(?s).*\\[\\d+\\].*");
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
