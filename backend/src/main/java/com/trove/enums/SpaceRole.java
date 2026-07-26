/*
 * ============================================================================
 *  SpaceRole — the three membership roles in a space
 * ============================================================================
 *  Purpose:        string constants for space_member.role values.
 *  Business use:    owner administers the space (add/remove members); member can
 *                  add/confirm documents; viewer is read-only.
 *  Design:         plain strings to match the DDL text exactly.
 * ============================================================================
 */
package com.trove.enums;

public final class SpaceRole {

    public static final String OWNER = "owner";
    public static final String MEMBER = "member";
    public static final String VIEWER = "viewer";

    private SpaceRole() {
    }

    /** Roles allowed to modify content (upload, confirm). Viewers are excluded. */
    public static boolean canWrite(String role) {
        return OWNER.equals(role) || MEMBER.equals(role);
    }
}
