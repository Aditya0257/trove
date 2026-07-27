/*
 * ============================================================================
 *  ApiError — uniform error response body
 * ============================================================================
 *  Purpose:        a consistent JSON error shape for every failed request.
 *  Business use:    clients (web/mobile/curl) get predictable, actionable errors.
 *  Design:         `details` optionally carries extras (e.g. existingDocumentId on
 *                  a 409) without needing a bespoke type per error. `notice` is the
 *                  two-channel user+developer envelope (D23) so clients can show a
 *                  calm message and the precise reason together. `message` is kept for
 *                  backward compatibility (it mirrors notice.userMessage).
 * ============================================================================
 */
package com.trove.exception;

import com.trove.common.ApiNotice;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, Object> details,
        ApiNotice notice
) {
    public static ApiError of(int status, String error, String message, String path,
                              Map<String, Object> details, ApiNotice notice) {
        return new ApiError(Instant.now(), status, error, message, path, details, notice);
    }
}
