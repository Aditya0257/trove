package com.trove.service;

import java.util.UUID;

/** Service contract for AccountDeletionService. */
public interface AccountDeletionService {
    void deleteAccount(UUID userId);
}
