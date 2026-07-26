/*
 * ============================================================================
 *  ChatController — "Ask your vault" endpoints
 * ============================================================================
 *  Purpose:        ask a question (grounded RAG answer + citations) and trigger an
 *                  on-demand reindex of a space (backfill without waiting for the sweep).
 *  Business use:    natural-language Q&A over the caller's documents.
 *  Design:         base path /api/chat (authenticated). Space defaults to the caller's
 *                  personal space; membership enforced in VaultChatService / here.
 * ============================================================================
 */
package com.trove.controllers;
import com.trove.service.impl.EmbeddingService;
import com.trove.service.impl.VaultChatService;

import com.trove.dto.ChatDtos.ChatAnswer;
import com.trove.security.CurrentUser;
import com.trove.security.SpaceAuthorization;
import com.trove.service.impl.SpaceService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final VaultChatService chatService;
    private final EmbeddingService embeddings;
    private final SpaceService spaceService;
    private final SpaceAuthorization authorization;
    private final CurrentUser currentUser;

    public ChatController(VaultChatService chatService, EmbeddingService embeddings, SpaceService spaceService,
                          SpaceAuthorization authorization, CurrentUser currentUser) {
        this.chatService = chatService;
        this.embeddings = embeddings;
        this.spaceService = spaceService;
        this.authorization = authorization;
        this.currentUser = currentUser;
    }

    /** Ask a question of the vault. */
    @PostMapping("/ask")
    public ChatAnswer ask(@RequestParam(value = "spaceId", required = false) UUID spaceId,
                          @RequestBody AskRequest body) {
        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        return chatService.ask(space, user, body == null ? null : body.question());
    }

    /** Embed any not-yet-indexed documents in a space now (instant backfill). */
    @PostMapping("/reindex")
    public Map<String, Integer> reindex(@RequestParam(value = "spaceId", required = false) UUID spaceId) {
        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        authorization.requireCanRead(space, user);
        List<UUID> stale = embeddings.staleDocumentIds(space, 500);
        int done = 0;
        for (UUID id : stale) {
            if (embeddings.index(id, user)) {
                done++;
            }
        }
        return Map.of("indexed", done);
    }

    public record AskRequest(String question) {
    }
}
