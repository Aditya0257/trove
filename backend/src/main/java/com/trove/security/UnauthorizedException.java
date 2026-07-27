/*
 * ============================================================================
 *  UnauthorizedException — bad/absent credentials (→ HTTP 401)
 * ============================================================================
 *  Purpose:        signal 401, e.g. wrong email/password on login.
 * ============================================================================
 */
package com.trove.security;

public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
