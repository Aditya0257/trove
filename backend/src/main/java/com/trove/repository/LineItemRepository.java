/*
 * ============================================================================
 *  LineItemRepository — data access for document line items
 * ============================================================================
 *  Purpose:        persist line items and clear them before re-extraction so a
 *                  re-run doesn't duplicate rows (idempotent extraction).
 * ============================================================================
 */
package com.trove.repository;
import com.trove.entity.LineItem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface LineItemRepository extends JpaRepository<LineItem, UUID> {

    List<LineItem> findByDocumentId(UUID documentId);

    /** Cleared before writing fresh line items so re-extraction stays idempotent. */
    @Transactional
    void deleteByDocumentId(UUID documentId);
}
