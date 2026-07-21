/*
 * ============================================================================
 *  SecurityNoticeHandler — two-channel notices for filter-level 401/403 (D23)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Spring Security rejects unauthenticated (401) and access-denied (403) requests in
 *  the filter chain, BEFORE @RestControllerAdvice can run — so those responses would
 *  otherwise have no body. This handler writes the same `ApiError` + `ApiNotice`
 *  envelope every other error uses, so clients get a friendly "please sign in again"
 *  with a precise developer note.
 *
 *  Business use case
 *  -----------------
 *  A silently empty 401 is exactly the opaque failure the Notice System (D23) exists
 *  to eliminate. An expired token should say so, calmly, everywhere.
 *
 *  Solution architecture
 *  ---------------------
 *  One @Component implementing BOTH AuthenticationEntryPoint (401) and
 *  AccessDeniedHandler (403); wired to both hooks in SecurityConfig. Serializes an
 *  ApiError with Jackson directly to the response.
 *
 *  Reasoning & logic
 *  -----------------
 *  Carries no secrets — just the reason category. Uses the shared ApiNotice codes so
 *  the clients switch on the same values as the REST-advice path.
 * ============================================================================
 */
package com.trove.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trove.common.error.ApiError;
import com.trove.common.notice.ApiNotice;
import com.trove.common.notice.NoticeLevel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class SecurityNoticeHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    public SecurityNoticeHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 401 — no/invalid credentials reached a protected endpoint. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, ApiNotice.of(NoticeLevel.WARNING, "UNAUTHENTICATED",
                "Please sign in to continue.",
                "No valid credentials on a protected endpoint (JWT absent, malformed, or expired)."));
    }

    /** 403 — authenticated, but not allowed. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, ApiNotice.of(NoticeLevel.WARNING, "FORBIDDEN",
                "You don't have access to this.",
                "Authenticated, but lacking the required role/membership for this resource."));
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, ApiNotice notice) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(),
                notice.userMessage(), request.getRequestURI(), notice.meta(), notice);
        mapper.writeValue(response.getWriter(), body);
    }
}
