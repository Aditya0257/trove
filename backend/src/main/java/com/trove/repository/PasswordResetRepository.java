/*
 * ============================================================================
 *  PasswordResetRepository - data access for reset tokens
 * ============================================================================
 */
package com.trove.repository;
import com.trove.entity.PasswordResetToken;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PasswordResetRepository extends JpaRepository<PasswordResetToken, UUID> {
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);
}
