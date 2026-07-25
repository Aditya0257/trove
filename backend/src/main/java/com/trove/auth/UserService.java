/*
 * ============================================================================
 *  UserService — registration and credential verification
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Registers new accounts (hashing the password and creating a personal space) and
 *  verifies credentials on login.
 *
 *  Business use case
 *  -----------------
 *  Onboarding: a new user gets an account AND their private personal space in one
 *  step, so they can start filing documents immediately.
 *
 *  Solution architecture
 *  ---------------------
 *  Uses BCrypt (PasswordEncoder) for hashing, UserRepository for persistence, and
 *  SpaceService to provision the personal space. Token issuing lives in the
 *  controller (JwtService) so this service stays about identity, not transport.
 *
 *  Reasoning & logic
 *  -----------------
 *  Email is normalized (trimmed/lowercased) for consistent uniqueness. Login errors
 *  are deliberately vague (same message for unknown email vs wrong password) to
 *  avoid leaking which emails are registered.
 * ============================================================================
 */
package com.trove.auth;

import com.trove.common.error.ConflictException;
import com.trove.common.error.NotFoundException;
import com.trove.common.error.UnauthorizedException;
import com.trove.notification.EmailSender;
import com.trove.space.SpaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    static final String ACTIVE = "active";
    static final String PENDING = "pending";
    static final String REJECTED = "rejected";
    static final String UNVERIFIED = "unverified"; // email OTP not yet confirmed

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SpaceService spaceService;
    private final EmailSender emailSender;
    /** The one admin who approves new sign-ups. Blank = open registration (no gate). */
    private final String adminEmail;
    private final String webBaseUrl;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       SpaceService spaceService, EmailSender emailSender,
                       @Value("${trove.admin.email:}") String adminEmail,
                       @Value("${trove.web.base-url:http://localhost:4200}") String webBaseUrl) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.spaceService = spaceService;
        this.emailSender = emailSender;
        this.adminEmail = adminEmail == null ? "" : adminEmail.trim();
        this.webBaseUrl = webBaseUrl;
    }

    /** True when this account is the configured admin (approves sign-ups). */
    public boolean isAdmin(User user) {
        return !adminEmail.isBlank() && user != null && adminEmail.equalsIgnoreCase(user.getEmail());
    }

    /** Registers a new user (hashed password) and provisions their personal space. */
    @Transactional
    public User register(String email, String displayName, String password) {
        String normalized = normalize(email);
        if (normalized.isBlank() || password == null || password.length() < 8) {
            throw new IllegalArgumentException("Email and a password of at least 8 characters are required");
        }
        if (userRepository.existsByEmailIgnoreCase(normalized)) {
            throw new ConflictException("Email already registered");
        }
        String name = (displayName == null || displayName.isBlank()) ? normalized : displayName.trim();
        User user = new User(normalized, name, passwordEncoder.encode(password));
        // Every new account must first verify its email (OTP). It starts 'unverified' and
        // only reaches the admin-approval gate once the email is confirmed (finishVerification).
        user.setStatus(UNVERIFIED);
        user = userRepository.save(user);
        spaceService.createPersonalSpace(user.getId(), name);
        return user;
    }

    /**
     * Called once the email OTP is confirmed: applies the approval gate. With no admin
     * configured (open registration) or for the admin's own account the user goes ACTIVE;
     * otherwise it goes PENDING and the admin is emailed the access request. Returns the
     * updated user so the caller can mint a token when ACTIVE.
     */
    @Transactional
    public User finishVerification(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (!UNVERIFIED.equals(user.getStatus())) {
            return user; // already verified/active/pending; idempotent
        }
        boolean gated = !adminEmail.isBlank();
        boolean isTheAdmin = gated && adminEmail.equalsIgnoreCase(user.getEmail());
        user.setStatus(!gated || isTheAdmin ? ACTIVE : PENDING);
        user = userRepository.save(user);
        if (PENDING.equals(user.getStatus())) {
            notifyAdminOfRequest(user);
        }
        return user;
    }

    /** Verifies credentials, returning the user or throwing 401. The caller checks status
     *  (an approved password on a pending account still must not yield a token). */
    @Transactional(readOnly = true)
    public User authenticate(String email, String password) {
        User user = userRepository.findByEmailIgnoreCase(normalize(email))
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }
        return user;
    }

    /** Accounts awaiting approval (admin view). */
    @Transactional(readOnly = true)
    public List<User> pendingUsers() {
        return userRepository.findByStatusOrderByCreatedAtAsc(PENDING);
    }

    /** Approves a pending account and emails the person that they can sign in. */
    @Transactional
    public void approve(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        user.setStatus(ACTIVE);
        userRepository.save(user);
        emailSender.send(List.of(user.getEmail()), "Your Trove access is approved",
                "Hi " + user.getDisplayName() + ",\n\nYour Trove account has been approved. "
                        + "You can now sign in: " + webBaseUrl.replaceAll("/+$", "") + "/login");
        log.info("Approved account {}", user.getEmail());
    }

    /** Declines a pending account (kept as rejected; can't sign in). */
    @Transactional
    public void reject(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        user.setStatus(REJECTED);
        userRepository.save(user);
        log.info("Rejected account {}", user.getEmail());
    }

    private void notifyAdminOfRequest(User user) {
        if (adminEmail.isBlank()) {
            return;
        }
        emailSender.send(List.of(adminEmail), "New Trove access request",
                user.getDisplayName() + " (" + user.getEmail() + ") requested access to Trove. "
                        + "Approve or decline in the app: " + webBaseUrl.replaceAll("/+$", "") + "/admin");
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
