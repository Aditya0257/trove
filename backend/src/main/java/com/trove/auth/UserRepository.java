/*
 * ============================================================================
 *  UserRepository — data access for accounts
 * ============================================================================
 *  Purpose:        find users by email (login, inviting members) and check
 *                  existence (registration).
 * ============================================================================
 */
package com.trove.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
