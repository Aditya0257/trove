/*
 * ============================================================================
 *  MerchantRepository — data access for canonical merchants
 * ============================================================================
 *  Purpose:        find a merchant by canonical name (case-insensitive).
 * ============================================================================
 */
package com.trove.repository;
import com.trove.entity.Merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    Optional<Merchant> findByCanonicalNameIgnoreCase(String canonicalName);

    /** Fuzzy match by canonical name, used to resolve free-text search to merchants. */
    List<Merchant> findByCanonicalNameContainingIgnoreCase(String fragment);
}
