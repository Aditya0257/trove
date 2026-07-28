/*
 * ============================================================================
 *  JoinResult — outcome of opening a space join link
 * ============================================================================
 *  Purpose:  tell the client whether opening a join link created a pending
 *            request (the owner must approve) or was a no-op because the caller
 *            is already a member — so the join page shows the right message
 *            instead of always claiming "request sent, wait for approval".
 * ============================================================================
 */
package com.trove.dto;

import java.util.UUID;

public record JoinResult(UUID spaceId, String spaceName, String status) {

    /** A new pending request was created; the owner still needs to approve. */
    public static final String REQUESTED = "requested";

    /** The caller was already an active member (owner or member) — nothing changed. */
    public static final String ALREADY_MEMBER = "already_member";
}
