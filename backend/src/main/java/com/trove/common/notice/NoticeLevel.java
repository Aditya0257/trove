/*
 * ============================================================================
 *  NoticeLevel — severity/tone of a user-facing notice
 * ============================================================================
 *  Purpose:        classify a Notice so clients can style it (colour, icon, toast
 *                  duration) consistently across web and mobile.
 *  Business use:    the Notice System (D23) is about dignifying every outcome — the
 *                  level drives how calm vs. attention-grabbing the presentation is.
 *  Design:         four levels only, matching common toast conventions. Serialized as
 *                  its lowercase name for ergonomic client-side switch statements.
 * ============================================================================
 */
package com.trove.common.notice;

import com.fasterxml.jackson.annotation.JsonValue;

public enum NoticeLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR;

    /** Serialize as "info"/"success"/… so clients switch on lowercase strings. */
    @JsonValue
    public String json() {
        return name().toLowerCase();
    }
}
