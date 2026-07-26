/*
 * ============================================================================
 *  AccountController - authenticated account settings (profile + security)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Everything a signed-in user manages about their own account: profile (display
 *  name, photo, email), password, and authenticator-app two-factor (TOTP).
 *
 *  Business use case
 *  -----------------
 *  The self-service "profile" area reached from the avatar in the top bar. Sensitive
 *  changes re-check a credential: changing the password or email needs the current
 *  password; turning 2FA off needs a live code. Deleting an account is deliberately
 *  NOT here - it is an admin-only action in AdminController, since a delete wipes every
 *  document and this vault optimises for zero data loss.
 *
 *  Solution architecture
 *  ---------------------
 *  Base path /api/account (authenticated: identity from CurrentUser). Profile photos are
 *  stored in object storage (R2) like any other file - never as bytes in the DB - and
 *  surfaced to the browser as a short-lived presigned URL so an <img> can load it without
 *  a bearer header. An email change parks the new address as pending and reuses the sign-up
 *  OTP machinery; the live email only moves once the code sent to the new address checks out.
 * ============================================================================
 */
package com.trove.controllers;
import com.trove.enums.EmailVerificationService;
import com.trove.service.impl.TotpService;
import com.trove.entity.User;
import com.trove.repository.UserRepository;
import com.trove.service.impl.UserService;

import com.trove.exception.NotFoundException;
import com.trove.security.UnauthorizedException;
import com.trove.security.CurrentUser;
import com.trove.security.EncryptionService;
import com.trove.integration.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/account")
public class AccountController {

    /** Avatars are small; cap the upload so a stray large file can't be stored as a photo. */
    private static final long MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    private static final Duration AVATAR_URL_TTL = Duration.ofHours(1);

    private final UserRepository userRepository;
    private final UserService userService;
    private final TotpService totpService;
    private final EncryptionService encryptionService;
    private final EmailVerificationService emailVerificationService;
    private final StorageService storageService;
    private final CurrentUser currentUser;

    public AccountController(UserRepository userRepository, UserService userService, TotpService totpService,
                            EncryptionService encryptionService, EmailVerificationService emailVerificationService,
                            StorageService storageService, CurrentUser currentUser) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.totpService = totpService;
        this.encryptionService = encryptionService;
        this.emailVerificationService = emailVerificationService;
        this.storageService = storageService;
        this.currentUser = currentUser;
    }

    // ── Profile ────────────────────────────────────────────────────────────────

    /** The signed-in user's profile + security summary, for the account screen and the nav avatar. */
    @GetMapping("/me")
    public AccountResponse me() {
        User u = me0();
        return new AccountResponse(u.getEmail(), u.getDisplayName(), userService.isAdmin(u),
                u.isTotpEnabled(), avatarUrl(u), u.getPendingEmail(), u.getCreatedAt());
    }

    /** Rename the account (name shown in the nav, spaces and sharing). */
    @PostMapping("/profile")
    public AccountResponse updateProfile(@RequestBody ProfileRequest req) {
        User u = userService.updateDisplayName(currentUser.requireUserId(), req.displayName());
        return new AccountResponse(u.getEmail(), u.getDisplayName(), userService.isAdmin(u),
                u.isTotpEnabled(), avatarUrl(u), u.getPendingEmail(), u.getCreatedAt());
    }

    /** Change the password after re-checking the current one. */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestBody PasswordChangeRequest req) {
        userService.changePassword(currentUser.requireUserId(), req.currentPassword(), req.newPassword());
    }

    // ── Email change (OTP-confirmed) ─────────────────────────────────────────────

    /** Start an email change: re-check the password, park the new address, and OTP it. */
    @PostMapping("/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void startEmailChange(@RequestBody EmailChangeRequest req) {
        User u = userService.startEmailChange(currentUser.requireUserId(), req.newEmail(), req.password());
        // Send the code to the NEW address so the change proves control of that inbox.
        emailVerificationService.send(u.getId(), u.getPendingEmail(), u.getDisplayName());
    }

    /** Confirm the pending email with the code sent to it. */
    @PostMapping("/email/verify")
    public AccountResponse verifyEmailChange(@RequestBody CodeRequest req) {
        UUID id = currentUser.requireUserId();
        EmailVerificationService.Result r = emailVerificationService.verify(id, req.code());
        if (r != EmailVerificationService.Result.OK) {
            throw new UnauthorizedException("That code is incorrect or expired. Request a new one and try again.");
        }
        User u = userService.finishEmailChange(id);
        return new AccountResponse(u.getEmail(), u.getDisplayName(), userService.isAdmin(u),
                u.isTotpEnabled(), avatarUrl(u), u.getPendingEmail(), u.getCreatedAt());
    }

    // ── Profile photo ────────────────────────────────────────────────────────────

    /** Upload (or replace) the profile photo. Stored in R2, keyed by user id. */
    @PostMapping("/photo")
    @Transactional
    public Map<String, String> uploadPhoto(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No image was provided");
        }
        String type = file.getContentType();
        if (type == null || !type.startsWith("image/")) {
            throw new IllegalArgumentException("The profile photo must be an image");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("Keep the photo under 2 MB");
        }
        User u = me0();
        String key = "_avatars/" + u.getId();
        try {
            storageService.put(key, file.getBytes(), type);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not read that image");
        }
        u.setAvatarKey(key);
        userRepository.save(u);
        return Map.of("avatarUrl", storageService.presignedUrl(key, AVATAR_URL_TTL));
    }

    /** Remove the profile photo (falls back to initials in the UI). */
    @DeleteMapping("/photo")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePhoto() {
        User u = me0();
        if (u.getAvatarKey() != null) {
            try {
                storageService.delete(u.getAvatarKey());
            } catch (Exception ignored) {
                // A missing object is fine; we only need the pointer cleared.
            }
            u.setAvatarKey(null);
            userRepository.save(u);
        }
    }

    // ── Two-factor (TOTP) ────────────────────────────────────────────────────────

    /** Whether 2FA is currently on for this account. */
    @GetMapping("/2fa/status")
    public Map<String, Boolean> status() {
        return Map.of("enabled", me0().isTotpEnabled());
    }

    /** Start enrollment: generate + store a fresh secret (not yet active), return it + the
     *  otpauth URI to add to an authenticator app. */
    @PostMapping("/2fa/setup")
    @Transactional
    public Map<String, String> setup() {
        User user = me0();
        String secret = totpService.newSecret();
        user.setTotpSecretEnc(encryptionService.encrypt(secret));
        user.setTotpEnabled(false);   // stays off until a code is verified
        userRepository.save(user);
        return Map.of("secret", secret, "otpauthUri", totpService.otpauthUri(secret, user.getEmail()));
    }

    /** Turn 2FA on after the user proves a valid code from the setup secret. */
    @PostMapping("/2fa/enable")
    @Transactional
    public void enable(@RequestBody CodeRequest req) {
        User user = me0();
        if (user.getTotpSecretEnc() == null) {
            throw new UnauthorizedException("Start setup first");
        }
        if (!totpService.verify(encryptionService.decrypt(user.getTotpSecretEnc()), req.code())) {
            throw new UnauthorizedException("That code didn't match. Check the app and try again.");
        }
        user.setTotpEnabled(true);
        userRepository.save(user);
    }

    /** Turn 2FA off; requires a current code so an open session can't silently disable it. */
    @PostMapping("/2fa/disable")
    @Transactional
    public void disable(@RequestBody CodeRequest req) {
        User user = me0();
        if (user.isTotpEnabled()
                && !totpService.verify(encryptionService.decrypt(user.getTotpSecretEnc()), req.code())) {
            throw new UnauthorizedException("That code didn't match, so 2FA was left on.");
        }
        user.setTotpEnabled(false);
        user.setTotpSecretEnc(null);
        userRepository.save(user);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private User me0() {
        UUID id = currentUser.requireUserId();
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException("Account not found"));
    }

    /** A short-lived presigned URL for the photo, or null if none is set. */
    private String avatarUrl(User u) {
        return u.getAvatarKey() == null ? null : storageService.presignedUrl(u.getAvatarKey(), AVATAR_URL_TTL);
    }

    public record ProfileRequest(String displayName) {
    }

    public record PasswordChangeRequest(String currentPassword, String newPassword) {
    }

    public record EmailChangeRequest(String newEmail, String password) {
    }

    public record CodeRequest(String code) {
    }

    public record AccountResponse(String email, String displayName, boolean admin, boolean twoFactorEnabled,
                                  String avatarUrl, String pendingEmail, Instant createdAt) {
    }
}
