/*
 * ============================================================================
 *  DevProperties — seeded identity used before real auth exists
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Binds trove.dev.* from application.yml: the fixed user + space UUIDs seeded by
 *  Flyway V6. Endpoints fall back to these when there is no authenticated user.
 *
 *  Business use case
 *  -----------------
 *  Slice 1 needs the upload/list/confirm flow testable without a login. These IDs
 *  give every request a real space + uploader. Full auth (JWT, membership) is a
 *  later phase and will replace these defaults — see DECISIONS.md → D6.
 *
 *  Solution architecture
 *  ---------------------
 *  Values MUST match the fixed UUIDs inserted by V6__seed_dev_user_space_categories.
 *
 *  Reasoning & logic
 *  -----------------
 *  Kept as typed config (not hard-coded constants) so switching to a different
 *  seed, or removing this once auth lands, is a config/annotation change only.
 * ============================================================================
 */
package com.trove.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConfigurationProperties(prefix = "trove.dev")
public class DevProperties {

    /** Seeded default uploader (app_user.id from V6). */
    private UUID defaultUserId;

    /** Seeded default personal space (space.id from V6). */
    private UUID defaultSpaceId;

    public UUID getDefaultUserId() {
        return defaultUserId;
    }

    public void setDefaultUserId(UUID defaultUserId) {
        this.defaultUserId = defaultUserId;
    }

    public UUID getDefaultSpaceId() {
        return defaultSpaceId;
    }

    public void setDefaultSpaceId(UUID defaultSpaceId) {
        this.defaultSpaceId = defaultSpaceId;
    }
}
