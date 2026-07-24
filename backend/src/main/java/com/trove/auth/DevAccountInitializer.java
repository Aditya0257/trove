/*
 * ============================================================================
 *  DevAccountInitializer — gives the seeded dev user a real, hashed login
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  On startup, if the seeded dev user still has the non-login placeholder hash
 *  ("SEED-NO-LOGIN") and a dev password is configured, replace it with a proper
 *  BCrypt hash so you can log in as the dev user.
 *
 *  Business use case
 *  -----------------
 *  Slice 1/2 used a seeded dev user with no password. Now that auth is real, this
 *  keeps the dev account usable for quick local testing without hand-editing hashes
 *  (DECISIONS.md → D6/D10).
 *
 *  Solution architecture
 *  ---------------------
 *  Runs after Flyway (ApplicationRunner). Idempotent: it only acts while the hash is
 *  the placeholder, so it never overwrites a real password. No-op if
 *  trove.dev.default-password is blank (e.g. in production).
 *
 *  Reasoning & logic
 *  -----------------
 *  Logs the dev login (email + configured password) at startup for convenience —
 *  acceptable because this only runs with an explicitly configured dev password,
 *  which you would never set in prod.
 * ============================================================================
 */
package com.trove.auth;

import com.trove.common.DevProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DevAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAccountInitializer.class);
    private static final String PLACEHOLDER = "SEED-NO-LOGIN";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DevProperties devProperties;

    public DevAccountInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                 DevProperties devProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.devProperties = devProperties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String devPassword = devProperties.getDefaultPassword();
        if (devPassword == null || devPassword.isBlank() || devProperties.getDefaultUserId() == null) {
            return; // dev login disabled
        }
        userRepository.findById(devProperties.getDefaultUserId()).ifPresent(user -> {
            if (PLACEHOLDER.equals(user.getPasswordHash())) {
                user.setPasswordHash(passwordEncoder.encode(devPassword));
                userRepository.save(user);
                log.info("Dev login enabled - email='{}' password='{}' (configure trove.dev.default-password to change/disable)",
                        user.getEmail(), devPassword);
            }
        });
    }
}
