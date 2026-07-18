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
import com.trove.common.error.UnauthorizedException;
import com.trove.space.SpaceService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SpaceService spaceService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       SpaceService spaceService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.spaceService = spaceService;
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
        User user = userRepository.save(new User(normalized, name, passwordEncoder.encode(password)));
        spaceService.createPersonalSpace(user.getId(), name);
        return user;
    }

    /** Verifies credentials, returning the user or throwing 401. */
    @Transactional(readOnly = true)
    public User authenticate(String email, String password) {
        User user = userRepository.findByEmailIgnoreCase(normalize(email))
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }
        return user;
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
