/*
 * ============================================================================
 *  AiUsageTracker — persistent, per-user + global AI consumption accounting
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  One Cloudflare Workers AI account backs the whole app, and its free allowance is
 *  10,000 neurons/day shared by everyone. This records consumption per UTC day, both
 *  as a global aggregate and per user, in the ai_usage table (V11) so it survives
 *  restarts and drives the Developer gauge (global + your-usage, neurons + tokens).
 *
 *  Solution architecture
 *  ---------------------
 *  A tiny JdbcTemplate upsert per AI call bumps the day's global row (the all-zero
 *  UUID) and the caller's own row. Neurons are Cloudflare's billed unit and the real
 *  limit; tokens are the API's human-readable figure, kept alongside. The neuron cost
 *  of a call is derived from the model's published per-token rates.
 *
 *  Reasoning & logic
 *  -----------------
 *  Cloudflare doesn't return neurons per request, only tokens — so we convert tokens
 *  to neurons with each model's input/output rates. Not exact to the cent (the CF
 *  dashboard is authoritative), but a truthful, restart-safe estimate against 10,000.
 * ============================================================================
 */
package com.trove.extraction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class AiUsageTracker {

    /** Cloudflare Workers AI free allowance, shared by the whole app. */
    public static final int DAILY_NEURON_LIMIT = 10_000;

    /** The aggregate ("all users") row is stored under this sentinel user id. */
    private static final UUID GLOBAL = new UUID(0L, 0L);

    private final JdbcTemplate jdbc;

    public AiUsageTracker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Record one AI call's cost against both the global total and the user's slice. */
    public void record(UUID userId, double neurons, long tokens) {
        if (neurons <= 0 && tokens <= 0) {
            return;
        }
        upsert(GLOBAL, neurons, tokens);
        if (userId != null && !GLOBAL.equals(userId)) {
            upsert(userId, neurons, tokens);
        }
    }

    /** Today's global (whole-app) usage. */
    public Usage globalToday() {
        return read(GLOBAL);
    }

    /** Today's usage for one user. */
    public Usage userToday(UUID userId) {
        return read(userId == null ? GLOBAL : userId);
    }

    private void upsert(UUID uid, double neurons, long tokens) {
        jdbc.update(
                "insert into ai_usage (day, user_id, neurons, tokens) values (?, ?, ?, ?) "
                        + "on conflict (day, user_id) do update set "
                        + "neurons = ai_usage.neurons + excluded.neurons, "
                        + "tokens = ai_usage.tokens + excluded.tokens",
                LocalDate.now(ZoneOffset.UTC), uid, neurons, tokens);
    }

    private Usage read(UUID uid) {
        return jdbc.query(
                "select neurons, tokens from ai_usage where day = ? and user_id = ?",
                rs -> rs.next() ? new Usage(rs.getDouble(1), rs.getLong(2)) : new Usage(0, 0),
                LocalDate.now(ZoneOffset.UTC), uid);
    }

    /** A day's usage figures. */
    public record Usage(double neurons, long tokens) {
    }

    /**
     * Convert a call's token counts to neurons using the model's published rates
     * (neurons per million tokens). Falls back to a middle estimate for unknown models.
     */
    public static double neuronsFor(String model, long promptTokens, long completionTokens) {
        String m = model == null ? "" : model.toLowerCase();
        double inRate;
        double outRate;
        if (m.contains("llama-3.2-11b-vision")) {
            inRate = 4_410;
            outRate = 61_493;
        } else if (m.contains("llama-3.1-8b")) {
            inRate = 25_608;
            outRate = 75_147;
        } else {
            inRate = 10_000; // conservative fallback for an unmapped model
            outRate = 60_000;
        }
        return promptTokens / 1_000_000.0 * inRate + completionTokens / 1_000_000.0 * outRate;
    }
}
