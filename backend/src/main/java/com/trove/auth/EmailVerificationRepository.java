/*
 * ============================================================================
 *  EmailVerificationRepository — data access for the email-verification OTP
 * ============================================================================
 */
package com.trove.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {
}
