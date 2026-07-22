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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class AiUsageTracker {

    /** The aggregate ("all users") row is stored under this sentinel user id. */
    private static final UUID GLOBAL = new UUID(0L, 0L);

    private final JdbcTemplate jdbc;

    /** Cloudflare Workers AI free allowance, shared by the whole app (neurons/day). */
    private final int dailyNeuronLimit;

    /**
     * Per-user slice of the shared allowance. Caps any single user so one heavy
     * uploader can't drain the day's budget for everyone else (default 20%).
     */
    private final int perUserNeuronLimit;

    public AiUsageTracker(JdbcTemplate jdbc,
                          @Value("${trove.ai.daily-neuron-limit:10000}") int dailyNeuronLimit,
                          @Value("${trove.ai.per-user-neuron-limit:2000}") int perUserNeuronLimit) {
        this.jdbc = jdbc;
        this.dailyNeuronLimit = dailyNeuronLimit;
        this.perUserNeuronLimit = perUserNeuronLimit;
    }

    public int dailyNeuronLimit() {
        return dailyNeuronLimit;
    }

    public int perUserNeuronLimit() {
        return perUserNeuronLimit;
    }

    /**
     * Null if this user may make an AI call right now; otherwise a short, human reason
     * it's blocked. Checked before any real (billed) provider runs — the global ceiling
     * first, then the caller's own slice. Extraction still completes via the stub.
     */
    public String blockReason(UUID userId) {
        if (globalToday().neurons() >= dailyNeuronLimit) {
            return "The app's shared AI budget for today is used up (" + dailyNeuronLimit
                    + " neurons). It resets at 00:00 UTC.";
        }
        if (userId != null && !GLOBAL.equals(userId)
                && userToday(userId).neurons() >= perUserNeuronLimit) {
            return "You've reached your daily AI limit (" + perUserNeuronLimit
                    + " neurons). It resets at 00:00 UTC.";
        }
        return null;
    }

    /**
     * Record one AI call's cost against both the global total and the user's slice.
     * Runs in its OWN transaction (REQUIRES_NEW): the caller may be a read-only
     * transaction (e.g. search) where this INSERT would otherwise fail and poison the
     * surrounding transaction. It's also the right semantics — neurons consumed are
     * consumed, and stay recorded even if the surrounding request later fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
