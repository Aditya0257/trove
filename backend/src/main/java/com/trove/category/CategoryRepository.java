/*
 * ============================================================================
 *  CategoryRepository — data access for categories
 * ============================================================================
 *  Purpose:        look up categories by code, scoped to a space or global.
 *  Design:         Spring Data derived queries; no custom SQL needed for Slice 1.
 * ============================================================================
 */
package com.trove.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    /** A space-specific category overriding a global one, if present. */
    Optional<Category> findBySpaceIdAndCode(UUID spaceId, String code);

    /** A global/system category (space_id IS NULL). */
    Optional<Category> findBySpaceIdIsNullAndCode(String code);
}
