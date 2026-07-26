/*
 * ============================================================================
 *  MerchantAliasRepository — data access for merchant aliases
 * ============================================================================
 *  Purpose:        resolve a raw OCR name to a known alias (case-insensitive).
 * ============================================================================
 */
package com.trove.repository;
import com.trove.entity.MerchantAlias;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MerchantAliasRepository extends JpaRepository<MerchantAlias, UUID> {

    Optional<MerchantAlias> findByAliasIgnoreCase(String alias);
}
