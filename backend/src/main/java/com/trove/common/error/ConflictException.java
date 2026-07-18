/*
 * ============================================================================
 *  ConflictException — a uniqueness/state conflict (→ HTTP 409)
 * ============================================================================
 *  Purpose:        signal 409, e.g. registering an email that already exists.
 * ============================================================================
 */
package com.trove.common.error;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
