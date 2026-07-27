/*
 * ============================================================================
 *  NotFoundException — a requested resource does not exist
 * ============================================================================
 *  Purpose:        signal 404 from the service layer without coupling to HTTP.
 *  Business use:    e.g. confirming a document id that isn't in this space.
 *  Design:         mapped to HTTP 404 by ApiExceptionHandler.
 * ============================================================================
 */
package com.trove.exception;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
