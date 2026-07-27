/*
 * ============================================================================
 *  UserRepository — data access for accounts
 * ============================================================================
 *  Purpose:        find users by email (login, inviting members) and check
 *                  existence (registration).
 * ============================================================================
 */
package com.trove.repository;
import com.trove.entity.User;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** Accounts in a given lifecycle status, oldest first (the admin approval queue). */
    List<User> findByStatusOrderByCreatedAtAsc(String status);
}
