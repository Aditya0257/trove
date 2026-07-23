/*
 * ============================================================================
 *  ChatDtos — API shapes for "Ask your vault"
 * ============================================================================
 *  Purpose:        the answer + the cited source documents a client renders.
 *  Design:         Citation carries just enough to show a chip and link to the doc;
 *                  aiUsed=false means retrieval-only (AI summary paused / disabled).
 * ============================================================================
 */
package com.trove.chat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ChatDtos {

    public record Citation(String documentId, int index, String title, String category,
                           LocalDate docDate, BigDecimal amount, String currency, String snippet) {
    }

    public record ChatAnswer(String answer, boolean aiUsed, List<Citation> sources) {
    }

    private ChatDtos() {
    }
}
