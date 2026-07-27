package com.trove.service;

import com.trove.dto.ChatDtos.ChatAnswer;
import java.util.UUID;

/** Service contract for VaultChatService. */
public interface VaultChatService {
    ChatAnswer ask(UUID spaceId, UUID userId, String question);
}
