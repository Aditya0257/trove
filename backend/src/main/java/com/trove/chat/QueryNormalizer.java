/*
 * ============================================================================
 *  QueryNormalizer - rewrite a question into an English search query
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Turns a Hindi or Hinglish question into a concise English search query before it is
 *  embedded for retrieval.
 *
 *  Business use case
 *  -----------------
 *  Users here type in English, Hindi, or romanized Hinglish ("kya meri saari tax
 *  receipts dikhao"). The vault's documents (Indian bills, receipts, policies) are
 *  printed in English, and the embedding model is English-only, so a non-English query
 *  vector does not line up with the English document vectors and retrieval finds nothing.
 *
 *  Solution architecture
 *  ---------------------
 *  Fix the asymmetry on the QUERY side, not the index: translate the query to English for
 *  retrieval while the original question still drives the answer (so the assistant can
 *  reply in the user's own language). Swapping to a multilingual embedder would need a
 *  1024-dim column and a full re-index for a corpus that is already English - avoided.
 *
 *  Reasoning & logic
 *  -----------------
 *  Cost-aware: a plain-English question is detected by a cheap heuristic and passed
 *  through with no model call. Only a likely non-English question spends one tiny
 *  router-model call, and any failure falls back to the original text, so retrieval can
 *  never get worse than before.
 * ============================================================================
 */
package com.trove.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class QueryNormalizer {

    private static final Logger log = LoggerFactory.getLogger(QueryNormalizer.class);

    /** Distinctive romanized-Hindi tokens. None are English words, so a whole-token match is a
     *  strong signal the question is Hinglish and worth translating for search. */
    private static final Set<String> HINGLISH_MARKERS = Set.of(
            "kya", "hai", "hain", "mujhe", "mera", "meri", "mere", "saari", "sari", "sabhi",
            "dikhao", "dikha", "dikhaega", "dikhado", "batao", "bata", "chahiye", "kitna",
            "kitni", "kitne", "kaise", "kaun", "kab", "kahan", "nahi", "nahin", "karo",
            "wala", "wali", "yeh", "woh", "aur", "hoga", "hogi", "kaunsa", "konsa");

    private final ChatProperties props;
    private final CloudflareChatClient chat;

    public QueryNormalizer(ChatProperties props, CloudflareChatClient chat) {
        this.props = props;
        this.chat = chat;
    }

    /** Returns an English search query for {@code question}. An English question is returned
     *  unchanged; a Hindi/Hinglish one is translated by a small model, falling back to the
     *  original text on any error or empty reply. */
    public String toSearchQuery(String question, UUID userId) {
        if (question == null || question.isBlank() || looksEnglish(question)) {
            return question;
        }
        try {
            String rewrite = clean(chat.chat(props.getRouterModel(), prompt(question),
                    40, 0, props.getTimeoutSeconds(), userId));
            if (rewrite.isBlank()) {
                return question;
            }
            log.debug("Normalized non-English query '{}' -> '{}'", question, rewrite);
            return rewrite;
        } catch (Exception e) {
            log.warn("Query normalization failed, using the original question: {}", e.getMessage());
            return question;
        }
    }

    /** True when the text is plain ASCII and carries no distinctive Hinglish marker token,
     *  i.e. it is almost certainly already an English query and needs no translation. */
    private boolean looksEnglish(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false; // non-ASCII (e.g. Devanagari) -> translate
            }
        }
        for (String token : text.toLowerCase().split("[^a-z]+")) {
            if (HINGLISH_MARKERS.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private String prompt(String question) {
        return """
            Rewrite the user's question as a short English search query for a personal document
            vault (bills, receipts, warranties, subscriptions). Translate any Hindi or Hinglish to
            English. Keep only the key terms (category, merchant, dates, amounts). Do not answer the
            question. Output only the query, nothing else.

            Question: %s
            English search query:""".formatted(question);
    }

    /** Trims the reply to one clean line, dropping any label or wrapping quotes the small model
     *  sometimes adds. */
    private String clean(String s) {
        if (s == null) {
            return "";
        }
        String out = s.trim();
        int newline = out.indexOf('\n');
        if (newline >= 0) {
            out = out.substring(0, newline).trim();
        }
        out = out.replaceFirst("(?i)^(english search query|query)\\s*[:\\-]\\s*", "").trim();
        if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
            out = out.substring(1, out.length() - 1).trim();
        }
        return out;
    }
}
