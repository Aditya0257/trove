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
package com.trove.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    /** Create an account (+ personal space) and return a token. */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest req) {
        User user = userService.register(req.email(), req.displayName(), req.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(tokenFor(user));
    }

    /** Verify credentials and return a token. */
    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest req) {
        User user = userService.authenticate(req.email(), req.password());
        return tokenFor(user);
    }

    private AuthResponse tokenFor(User user) {
        String token = jwtService.issue(user.getId(), user.getEmail(), user.getDisplayName());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getDisplayName());
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record RegisterRequest(@Email @NotBlank String email, String displayName,
                                  @NotBlank String password) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record AuthResponse(String token, UUID userId, String email, String displayName) {
    }
}
