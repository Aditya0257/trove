package com.trove.service;

import com.trove.dto.MailPage;
import java.util.UUID;

/** Service contract for MailService. */
public interface MailService {
    MailPage bundles(UUID spaceId, UUID userId, int page, int size);
}
