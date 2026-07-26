/*
 * ============================================================================
 *  AuthUser — the authenticated principal placed in the security context
 * ============================================================================
 *  Purpose:        the minimal identity carried per request after a valid JWT:
 *                  user id + email.
 *  Business use:    every space/document check needs "who is this?"; this is the
 *                  answer, resolved once per request by the JWT filter.
 *  Design:         intentionally tiny (no password/roles) — space roles are looked
 *                  up per-space from space_member, not baked into the token.
 * ============================================================================
 */
package com.trove.security;

import java.util.UUID;

public record AuthUser(UUID id, String email) {
}
