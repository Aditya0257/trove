/*
 * ============================================================================
 *  RequestDiagnosticsFilter — per-request correlation id + timing (D23)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Stamps every API request/response with a short correlation id and makes it
 *  available to logs (MDC) and to clients (the `X-Trove-Request-Id` response header).
 *  Records server-side handling time and best-effort adds `X-Trove-Duration-Ms`.
 *
 *  Business use case
 *  -----------------
 *  The Notice System (D23) turns the app legible: the web/mobile Developer surfaces
 *  show a request id per call so a user report ("it hiccuped") ties straight to a
 *  server log line — without exposing anything sensitive.
 *
 *  Solution architecture
 *  ---------------------
 *  A OncePerRequestFilter registered ahead of the dispatcher. Honours an inbound
 *  `X-Trove-Request-Id` (so a client can propagate its own), else mints one. The id
 *  is set on the response FIRST (before the chain) so it survives even when the
 *  response commits mid-handler; duration is added afterward only if the response
 *  isn't yet committed (large streamed downloads may already be flushed — acceptable,
 *  the client measures round-trip time itself).
 *
 *  Reasoning & logic
 *  -----------------
 *  Contains NO secrets — a random id and a millisecond count only. MDC is cleared in
 *  a finally block so ids never bleed across pooled threads.
 * ============================================================================
 */
package com.trove.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestDiagnosticsFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Trove-Request-Id";
    public static final String DURATION_HEADER = "X-Trove-Duration-Ms";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }
        // Set before the chain so the id is present even if the response commits mid-handler.
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (!response.isCommitted()) {
                response.setHeader(DURATION_HEADER, Long.toString(ms));
            }
            MDC.remove(MDC_KEY);
        }
    }
}
