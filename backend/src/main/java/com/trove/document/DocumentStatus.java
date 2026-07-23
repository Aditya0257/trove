/*
 * ============================================================================
 *  DocumentStatus — the states a document can be in
 * ============================================================================
 *  Purpose:        string constants for the document.status column values.
 *  Business use:    the brief is emphatic — nothing is trusted until a HUMAN
 *                  confirms. Every extraction lands in needs_review. DELETED is a
 *                  soft-delete tombstone: the row and files survive (in trash) for a
 *                  retention window so an accidental delete can be undone.
 *  Design:         plain string constants (not an enum) so the persisted value
 *                  matches the DDL text exactly.
 * ============================================================================
 */
package com.trove.document;

public final class DocumentStatus {

    public static final String NEEDS_REVIEW = "needs_review";
    public static final String CONFIRMED = "confirmed";
    public static final String DELETED = "deleted";

    private DocumentStatus() {
    }
}
