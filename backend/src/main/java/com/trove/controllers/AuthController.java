/*
 * ============================================================================
 *  AuthController — register and login (public endpoints)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  The only unauthenticated endpoints: create an account and obtain a JWT.
 *
 *  Business use case
 *  -----------------
 *  The front door. Register provisions an account + personal space; login returns a
 *  token the client then sends as `Authorization: Bearer <token>` on every call.
 *
 *  Solution architecture
 *  ---------------------
 *  Base path /api/auth (permitted in SecurityConfig). Delegates identity to
 *  UserService and token minting to JwtService. Request/response DTOs are nested.
 * ============================================================================
 */
package com.trove.controllers;
import com.trove.enums.EmailVerificationService;
import com.trove.entity.User;
import com.trove.repository.UserRepository;
import com.trove.security.JwtService;
import com.trove.service.PasswordResetService;
import com.trove.service.TotpService;
import com.trove.service.UserService;

import com.trove.security.UnauthorizedException;
import com.trove.security.EncryptionService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final TotpService totpService;
    private final EncryptionService encryptionService;
    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;
    private final UserRepository userRepository;

    public AuthController(UserService userService, JwtService jwtService, TotpService totpService,
                         EncryptionService encryptionService, PasswordResetService passwordResetService,
                         EmailVerificationService emailVerificationService, UserRepository userRepository) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.totpService = totpService;
        this.encryptionService = encryptionService;
        this.passwordResetService = passwordResetService;
        this.emailVerificationService = emailVerificationService;
        this.userRepository = userRepository;
    }

    /**
     * Create an account (+ personal space) and email a verification code. The account
     * starts "unverified" and gets NO token; the client shows the code-entry screen. Only
     * after the email is verified does the approval gate apply (see verifyEmail).
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AuthResponse register(@RequestBody RegisterRequest req) {
        User user = userService.register(req.email(), req.displayName(), req.password());
        emailVerificationService.send(user.getId(), user.getEmail(), user.getDisplayName());
        return new AuthResponse(null, user.getId(), user.getEmail(), user.getDisplayName(),
                false, false, user.getStatus()); // status = "unverified"
    }

    /**
     * Verify the sign-up email with the emailed 6-digit code. On success the approval gate
     * applies: an open-registration or admin account goes active (token returned); otherwise
     * it becomes pending and the admin is notified (status="pending", no token).
     */
    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@RequestBody VerifyEmailRequest req) {
        User user = userRepository.findByEmailIgnoreCase(normalize(req.email()))
                .orElseThrow(() -> new IllegalArgumentException("That code is not correct."));
        EmailVerificationService.Result result = emailVerificationService.verify(user.getId(), req.code());
        switch (result) {
            case OK -> {
                User updated = userService.finishVerification(user.getId());
                if ("active".equals(updated.getStatus())) {
                    return ResponseEntity.ok(tokenFor(updated));
                }
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                        new AuthResponse(null, updated.getId(), updated.getEmail(), updated.getDisplayName(),
                                false, false, updated.getStatus()));
            }
            case EXPIRED -> throw new IllegalArgumentException("That code has expired. Send yourself a new one.");
            case LOCKED -> throw new IllegalArgumentException("Too many tries. Send yourself a new code.");
            default -> throw new IllegalArgumentException("That code is not correct.");
        }
    }

    /** Resend the verification code. Always 204 (never leaks whether the email exists). */
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(@RequestBody ForgotRequest req) {
        userRepository.findByEmailIgnoreCase(normalize(req.email())).ifPresent(user -> {
            if ("unverified".equals(user.getStatus())) {
                emailVerificationService.send(user.getId(), user.getEmail(), user.getDisplayName());
            }
        });
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /**
     * Verify credentials and return a token. If the account has TOTP enabled, a correct
     * password with no code returns {twoFactorRequired:true} (no token); the client then
     * resubmits with the 6-digit code.
     */
    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest req) {
        User user = userService.authenticate(req.email(), req.password());
        // Correct password, but a pending/rejected account gets no token - the client reads
        // `status` and shows why (awaiting approval / declined).
        if (!"active".equals(user.getStatus())) {
            return new AuthResponse(null, user.getId(), user.getEmail(), user.getDisplayName(),
                    false, false, user.getStatus());
        }
        if (user.isTotpEnabled()) {
            if (req.code() == null || req.code().isBlank()) {
                return new AuthResponse(null, user.getId(), user.getEmail(), user.getDisplayName(),
                        true, false, user.getStatus());
            }
            if (!totpService.verify(encryptionService.decrypt(user.getTotpSecretEnc()), req.code())) {
                throw new UnauthorizedException("Invalid authenticator code");
            }
        }
        return tokenFor(user);
    }

    /** Start a password reset: emails a single-use link. Always 200 (never leaks existence). */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@RequestBody ForgotRequest req) {
        passwordResetService.requestReset(req.email());
    }

    /** Complete a password reset with the emailed token. */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@RequestBody ResetRequest req) {
        passwordResetService.reset(req.token(), req.newPassword());
    }

    private AuthResponse tokenFor(User user) {
        String token = jwtService.issue(user.getId(), user.getEmail(), user.getDisplayName());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getDisplayName(),
                false, userService.isAdmin(user), user.getStatus());
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record RegisterRequest(@Email @NotBlank String email, String displayName,
                                  @NotBlank String password) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password, String code) {
    }

    public record VerifyEmailRequest(@Email @NotBlank String email, @NotBlank String code) {
    }

    public record ForgotRequest(@Email @NotBlank String email) {
    }

    public record ResetRequest(@NotBlank String token, @NotBlank String newPassword) {
    }

    public record AuthResponse(String token, UUID userId, String email, String displayName,
                               boolean twoFactorRequired, boolean admin, String status) {
    }
}
