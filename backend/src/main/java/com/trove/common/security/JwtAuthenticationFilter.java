/*
 * ============================================================================
 *  JwtAuthenticationFilter — turns a Bearer token into an authenticated request
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  On each request, reads the Authorization: Bearer <token> header, validates it,
 *  and (if valid) puts the resolved AuthUser into the Spring Security context.
 *
 *  Business use case
 *  -----------------
 *  This is the gate: only requests carrying a valid token act as a real user; every
 *  space/document check downstream trusts the identity this filter established.
 *
 *  Solution architecture
 *  ---------------------
 *  OncePerRequestFilter registered before the username/password filter. It never
 *  rejects on its own — it just authenticates when possible; SecurityConfig decides
 *  which paths require authentication (so /api/auth/** stays open).
 *
 *  Reasoning & logic
 *  -----------------
 *  Stateless: no session is created. A missing/invalid token simply leaves the
 *  context unauthenticated, and protected endpoints then return 401/403.
 * ============================================================================
 */
package com.trove.common.security;

import com.trove.auth.AuthUser;
import com.trove.auth.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER.length());
            jwtService.parse(token).ifPresent(user -> authenticate(user, request));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(AuthUser user, HttpServletRequest request) {
        var authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
