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
package com.trove.service.impl;

import com.trove.service.UserService;
import com.trove.entity.User;
import com.trove.repository.UserRepository;

import com.trove.exception.ConflictException;
import com.trove.exception.NotFoundException;
import com.trove.security.UnauthorizedException;
import com.trove.integration.EmailSender;
import com.trove.service.SpaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);
    static final String ACTIVE = "active";
    static final String PENDING = "pending";
    static final String REJECTED = "rejected";
    static final String UNVERIFIED = "unverified"; // email OTP not yet confirmed

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SpaceService spaceService;
    private final EmailSender emailSender;
    /**
     * The admin email allow-list (lowercased). These accounts approve sign-ups and hold the
     * admin controls. Empty = open registration (no gate). Populated from two properties so a
     * deployment can name one or several admins without a code change and without a privileged
     * DB flag: trove.admin.emails (comma/space separated) plus the legacy single
     * trove.admin.email, unioned. To add a backup admin, add their email to the env var and
     * restart - nothing in the database decides who is admin.
     */
    private final Set<String> adminEmails;
    private final String webBaseUrl;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       SpaceService spaceService, EmailSender emailSender,
                       @Value("${trove.admin.email:}") String adminEmail,
                       @Value("${trove.admin.emails:}") String adminEmails,
                       @Value("${trove.web.base-url:http://localhost:4200}") String webBaseUrl) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.spaceService = spaceService;
        this.emailSender = emailSender;
        this.adminEmails = parseAdminEmails(adminEmail, adminEmails);
        this.webBaseUrl = webBaseUrl;
    }

    /** Merges the single- and list-valued admin properties into one lowercased set,
     *  splitting on commas, semicolons or whitespace and dropping blanks. */
    private static Set<String> parseAdminEmails(String single, String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String source : List.of(single == null ? "" : single, csv == null ? "" : csv)) {
            for (String part : source.split("[,;\\s]+")) {
                String e = part.trim().toLowerCase();
                if (!e.isBlank()) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** True when this account is one of the configured admins (approve sign-ups, admin controls). */
    public boolean isAdmin(User user) {
        return user != null && user.getEmail() != null
                && adminEmails.contains(user.getEmail().trim().toLowerCase());
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
        boolean gated = !adminEmails.isEmpty();
        boolean isTheAdmin = gated && isAdmin(user);
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
        String loginUrl = webBaseUrl.replaceAll("/+$", "") + "/login";
        String text = "Hi " + user.getDisplayName() + ",\n\n"
                + "Good news - your Trove account has been approved and is ready to use.\n\n"
                + "Sign in here: " + loginUrl + "\n\n"
                + "Trove keeps your bills, receipts, policies and IDs in one private vault, "
                + "with reminders before anything is due.";
        String html = buildApprovalHtml(user.getDisplayName(), loginUrl);
        emailSender.send(List.of(user.getEmail()), "Your Trove access is approved", text, html);
        log.info("Approved account {}", user.getEmail());
    }

    /** A themed welcome email matching the verification one: greeting, a short line, a clear
     *  sign-in button, and a one-line note on what Trove does. Inline styles for email clients. */
    private String buildApprovalHtml(String displayName, String loginUrl) {
        return """
            <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\
            max-width:480px;margin:0 auto;padding:8px 4px;color:#21252c;">
              <p style="font-size:15px;line-height:1.5;margin:0 0 6px;">Hi %s,</p>
              <p style="font-size:15px;line-height:1.5;margin:0 0 20px;">Good news - your Trove account is \
            approved and ready to use.</p>
              <div style="text-align:center;margin:0 0 22px;">
                <a href="%s" style="display:inline-block;background:#2f6f6a;color:#ffffff;text-decoration:none;\
            font-size:15px;font-weight:600;padding:13px 30px;border-radius:12px;">Sign in to Trove</a>
              </div>
              <p style="font-size:13px;line-height:1.6;color:#6c6a63;margin:0 0 4px;">Trove keeps your bills, \
            receipts, policies and IDs in one private vault, and reminds you before anything is due.</p>
              <p style="font-size:12px;line-height:1.55;color:#9a978d;margin:0;">If the button does not work, \
            paste this link into your browser: %s</p>
            </div>""".formatted(escape(displayName), loginUrl, loginUrl);
    }

    /** Minimal HTML escaping for the one interpolated free-text value (the display name). */
    private String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Declines a pending account (kept as rejected; can't sign in). */
    @Transactional
    public void reject(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        user.setStatus(REJECTED);
        userRepository.save(user);
        log.info("Rejected account {}", user.getEmail());
    }

    /** Loads an account by id (for the authenticated profile screens). */
    @Transactional(readOnly = true)
    public User require(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Account not found"));
    }

    /** Changes the signed-in user's password after re-checking the current one, so a
     *  walk-up attacker on an open session cannot silently reset it. */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = require(userId);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("Your current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("The new password must be at least 8 characters");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Password changed for account {}", user.getEmail());
    }

    /** Renames the signed-in user (the name shown in the nav, spaces and sharing). */
    @Transactional
    public User updateDisplayName(UUID userId, String displayName) {
        User user = require(userId);
        String name = displayName == null ? "" : displayName.trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("Display name cannot be empty");
        }
        user.setDisplayName(name);
        return userRepository.save(user);
    }

    /** Begins an email change: re-checks the password and that the new address is free, then
     *  parks it as pending. The caller sends an OTP to the new address; the live email only
     *  changes once {@link #finishEmailChange} runs on a correct code. */
    @Transactional
    public User startEmailChange(UUID userId, String newEmail, String password) {
        User user = require(userId);
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new UnauthorizedException("Your password is incorrect");
        }
        String normalized = normalize(newEmail);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("A valid email is required");
        }
        if (normalized.equalsIgnoreCase(user.getEmail())) {
            throw new IllegalArgumentException("That is already your email");
        }
        if (userRepository.existsByEmailIgnoreCase(normalized)) {
            throw new ConflictException("That email is already registered");
        }
        user.setPendingEmail(normalized);
        return userRepository.save(user);
    }

    /** Promotes a verified pending email to the live email (called after the OTP checks out). */
    @Transactional
    public User finishEmailChange(UUID userId) {
        User user = require(userId);
        if (user.getPendingEmail() != null && !user.getPendingEmail().isBlank()) {
            user.setEmail(user.getPendingEmail());
            user.setPendingEmail(null);
            user = userRepository.save(user);
            log.info("Email changed for account {}", user.getEmail());
        }
        return user;
    }

    private void notifyAdminOfRequest(User user) {
        if (adminEmails.isEmpty()) {
            return;
        }
        emailSender.send(new ArrayList<>(adminEmails), "New Trove access request",
                user.getDisplayName() + " (" + user.getEmail() + ") requested access to Trove. "
                        + "Approve or decline in the app: " + webBaseUrl.replaceAll("/+$", "") + "/admin");
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
