/*
 * ============================================================================
 *  ForbiddenException — the user is authenticated but not allowed
 * ============================================================================
 *  Purpose:        signal 403 from the service layer (e.g. not a member of a space,
 *                  or a viewer trying to write).
 *  Business use:    enforces space roles — the core of multi-user access control.
 *  Design:         mapped to HTTP 403 by ApiExceptionHandler.
 * ============================================================================
 */
package com.trove.common.error;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
