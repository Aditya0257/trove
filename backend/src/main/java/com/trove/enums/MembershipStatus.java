/*
 * ============================================================================
 *  MembershipStatus — lifecycle of a space membership
 * ============================================================================
 *  Purpose:        string constants for space_member.status.
 *  Business use:    an invite is PENDING until the invited user responds; ACTIVE
 *                  members can actually use the space; DECLINED records a refusal so
 *                  the owner sees it (and can dismiss the row) rather than silence.
 *  Design:         plain strings to match the DDL text exactly. Only ACTIVE grants
 *                  access — see SpaceAuthorization.
 * ============================================================================
 */
package com.trove.enums;

public final class MembershipStatus {

    public static final String ACTIVE = "active";
    public static final String PENDING = "pending";
    public static final String DECLINED = "declined";

    private MembershipStatus() {
    }
}
