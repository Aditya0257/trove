/*
 * ============================================================================
 *  CurrentUser — resolves the authenticated principal for the current request
 * ============================================================================
 *  Purpose:        a small helper so controllers/services can get the current
 *                  AuthUser (or just the user id) without touching Spring Security
 *                  plumbing directly.
 *  Business use:    every space/document operation is "on behalf of this user";
 *                  this is the single place that answers "who".
 *  Design:         reads the SecurityContext populated by JwtAuthenticationFilter;
 *                  requireUserId() throws 401-style if unauthenticated.
 * ============================================================================
 */
package com.trove.common.security;

import com.trove.auth.AuthUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class CurrentUser {

    /** The authenticated principal, if any. */
    public Optional<AuthUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /** The authenticated user id, or throws if the request is not authenticated. */
    public UUID requireUserId() {
        return get().map(AuthUser::id)
                .orElseThrow(() -> new IllegalStateException("No authenticated user in context"));
    }
}
