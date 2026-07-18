/*
 * ============================================================================
 *  ApiError — uniform error response body
 * ============================================================================
 *  Purpose:        a consistent JSON error shape for every failed request.
 *  Business use:    clients (web/mobile/curl) get predictable, actionable errors.
 *  Design:         `details` optionally carries extras (e.g. existingDocumentId on
 *                  a 409) without needing a bespoke type per error.
 * ============================================================================
 */
package com.trove.common.error;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, Object> details
) {
    public static ApiError of(int status, String error, String message, String path, Map<String, Object> details) {
        return new ApiError(Instant.now(), status, error, message, path, details);
    }
}
