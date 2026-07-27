package com.trove.service;

import com.trove.entity.User;
import java.util.List;
import java.util.UUID;

/** Service contract for UserService. */
public interface UserService {
    boolean isAdmin(User user);
    User register(String email, String displayName, String password);
    User finishVerification(UUID userId);
    User authenticate(String email, String password);
    List<User> pendingUsers();
    void approve(UUID userId);
    void reject(UUID userId);
    User require(UUID userId);
    void changePassword(UUID userId, String currentPassword, String newPassword);
    User updateDisplayName(UUID userId, String displayName);
    User startEmailChange(UUID userId, String newEmail, String password);
    User finishEmailChange(UUID userId);
}
