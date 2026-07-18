/*
 * ============================================================================
 *  DocumentStatus — the two review states a document can be in
 * ============================================================================
 *  Purpose:        string constants for the document.status column values.
 *  Business use:    the brief is emphatic — nothing is trusted until a HUMAN
 *                  confirms. Every extraction lands in needs_review.
 *  Design:         plain string constants (not an enum) so the persisted value
 *                  matches the DDL text exactly ('needs_review' | 'confirmed').
 * ============================================================================
 */
package com.trove.document;

public final class DocumentStatus {

    public static final String NEEDS_REVIEW = "needs_review";
    public static final String CONFIRMED = "confirmed";

    private DocumentStatus() {
    }
}
