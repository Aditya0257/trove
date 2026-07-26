/*
 * ============================================================================
 *  PasswordResetService - forgot/reset password flow
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Issues an emailed, single-use reset link and consumes it to set a new password.
 *
 *  Business use case
 *  -----------------
 *  Self-service recovery of a forgotten password (the username/email never changes).
 *
 *  Solution architecture
 *  ---------------------
 *  requestReset always succeeds silently - it never reveals whether an email is
 *  registered (anti-enumeration). A 32-byte URL-safe random token is emailed; only its
 *  SHA-256 hash is stored, with a 30-minute expiry. reset() hashes the presented token,
 *  checks it is unused + unexpired, updates the BCrypt hash, and marks it used.
 *
 *  Reasoning & logic
 *  -----------------
 *  Example: user asks to reset -> token "9f3c..." emailed as
 *  {webBaseUrl}/reset?token=9f3c... ; DB stores sha256("9f3c...") + expiry. On submit,
 *  we sha256 the token again, match the row, and set the new password. Using the link
 *  twice, or after 30 min, fails.
 * ============================================================================
 */
package com.trove.service.impl;
import com.trove.entity.PasswordResetToken;
import com.trove.entity.User;
import com.trove.repository.PasswordResetRepository;
import com.trove.repository.UserRepository;

import com.trove.common.HashUtil;
import com.trove.security.UnauthorizedException;
import com.trove.integration.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final long EXPIRY_MINUTES = 30;

    private final UserRepository userRepository;
    private final PasswordResetRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final SecureRandom random = new SecureRandom();
    private final String webBaseUrl;

    public PasswordResetService(UserRepository userRepository, PasswordResetRepository tokenRepository,
                                PasswordEncoder passwordEncoder, EmailSender emailSender,
                                @Value("${trove.web.base-url:http://localhost:4200}") String webBaseUrl) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.webBaseUrl = webBaseUrl;
    }

    /** Emails a reset link if the address is registered. Always returns quietly (no leak). */
    @Transactional
    public void requestReset(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        userRepository.findByEmailIgnoreCase(normalized).ifPresent(user -> {
            byte[] buf = new byte[32];
            random.nextBytes(buf);
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
            String hash = HashUtil.sha256Hex(rawToken.getBytes(StandardCharsets.UTF_8));
            tokenRepository.save(new PasswordResetToken(user.getId(), hash,
                    Instant.now().plus(EXPIRY_MINUTES, ChronoUnit.MINUTES)));

            String link = webBaseUrl.replaceAll("/+$", "") + "/reset?token=" + rawToken;
            String body = "Hi " + user.getDisplayName() + ",\n\n"
                    + "We received a request to reset your Trove password. Open the link below to choose a "
                    + "new one (it expires in " + EXPIRY_MINUTES + " minutes and works once):\n\n"
                    + link + "\n\n"
                    + "If you didn't ask for this, you can ignore this email; your password stays the same.";
            boolean sent = emailSender.send(java.util.List.of(user.getEmail()), "Reset your Trove password", body);
            if (!sent) {
                log.warn("Password reset requested for {} but email could not be sent (email not configured?)",
                        user.getEmail());
            }
        });
    }

    /** Consumes a token and sets the new password. Throws if invalid/expired/used/weak. */
    @Transactional
    public void reset(String rawToken, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        if (rawToken == null || rawToken.isBlank()) {
            throw new UnauthorizedException("This reset link is invalid or has expired");
        }
        String hash = HashUtil.sha256Hex(rawToken.trim().getBytes(StandardCharsets.UTF_8));
        PasswordResetToken token = tokenRepository.findByTokenHash(hash)
                .filter(t -> t.getUsedAt() == null)
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new UnauthorizedException("This reset link is invalid or has expired"));

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("This reset link is invalid or has expired"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        token.setUsedAt(Instant.now());
        tokenRepository.save(token);
        log.info("Password reset for user {}", user.getId());
    }
}
