package com.trove.service;

/** Service contract for PasswordResetService. */
public interface PasswordResetService {
    void requestReset(String email);
    void reset(String rawToken, String newPassword);
}
