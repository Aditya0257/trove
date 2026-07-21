/*
 * ============================================================================
 *  SecurityConfig — the HTTP security policy and password hashing
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Defines which endpoints are public vs authenticated, wires the JWT filter,
 *  makes the app stateless, and provides the BCrypt password encoder.
 *
 *  Business use case
 *  -----------------
 *  Trove holds people's most sensitive documents. This is the front door: only
 *  /api/auth/** (register, login) is open; everything else requires a valid token,
 *  and passwords are stored only as BCrypt hashes.
 *
 *  Solution architecture
 *  ---------------------
 *  Stateless session policy (no server-side sessions) fits the disposable host.
 *  The JwtAuthenticationFilter runs before the username/password filter and
 *  establishes identity from the Bearer token. CSRF is disabled because there are
 *  no cookies/sessions to protect (token in a header).
 *
 *  Reasoning & logic
 *  -----------------
 *  BCrypt (adaptive, salted) is the standard for password storage. CORS is
 *  permissive for dev; tighten allowedOrigins to the real client hosts in prod.
 * ============================================================================
 */
package com.trove.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityNoticeHandler securityNoticeHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          SecurityNoticeHandler securityNoticeHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.securityNoticeHandler = securityNoticeHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Filter-level 401/403 get the same two-channel notice body (D23).
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityNoticeHandler)
                        .accessDeniedHandler(securityNoticeHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        // Ingestion webhooks are public endpoints, gated by a shared
                        // secret inside the controllers (external services call them).
                        .requestMatchers("/api/ingest/**").permitAll()
                        // Google OAuth callback: the browser arrives from Google with
                        // no JWT; identity is carried in the signed state parameter.
                        .requestMatchers("/api/integrations/google-drive/callback").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** BCrypt: adaptive, salted password hashing. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** DEV CORS — tighten allowedOrigins to the real client hosts for production. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Expose Trove's diagnostic headers so browser JS (the Developer surface, D23)
        // can read the per-request correlation id despite the same-origin header allowlist.
        config.setExposedHeaders(List.of("X-Trove-Request-Id", "X-Trove-Duration-Ms"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
