/*
 * ============================================================================
 *  UsageServiceImpl — gather every free-tier meter into one UsageOverview
 * ============================================================================
 *  Purpose:  read the two daily-reset pools (AI neurons, email sends) and the three
 *            running-total storage figures (object store, database, mirror) and pack
 *            them for the Developer gauge, along with the next daily reset instant.
 *  Design:   AI + email come from their trackers; object-storage bytes are the sum of
 *            stored document sizes; database bytes come from pg_database_size; the
 *            mirror figure equals the object-store bytes when a mirror is configured
 *            (it mirrors the same documents). Free-tier ceilings are configurable.
 * ============================================================================
 */
package com.trove.service.impl;

import com.trove.config.MirrorProperties;
import com.trove.dto.Usage;
import com.trove.dto.UsageOverview;
import com.trove.repository.DocumentRepository;
import com.trove.service.UsageService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class UsageServiceImpl implements UsageService {

    private final AiUsageTracker ai;
    private final EmailUsageTracker email;
    private final DocumentRepository documents;
    private final JdbcTemplate jdbc;
    private final MirrorProperties mirror;

    private final long storageFreeBytes;
    private final long databaseFreeBytes;
    private final long mirrorFreeBytes;

    public UsageServiceImpl(AiUsageTracker ai, EmailUsageTracker email, DocumentRepository documents,
                            JdbcTemplate jdbc, MirrorProperties mirror,
                            @Value("${trove.storage.free-bytes:10737418240}") long storageFreeBytes,
                            @Value("${trove.database.free-bytes:536870912}") long databaseFreeBytes,
                            @Value("${trove.mirror.free-bytes:10737418240}") long mirrorFreeBytes) {
        this.ai = ai;
        this.email = email;
        this.documents = documents;
        this.jdbc = jdbc;
        this.mirror = mirror;
        this.storageFreeBytes = storageFreeBytes;
        this.databaseFreeBytes = databaseFreeBytes;
        this.mirrorFreeBytes = mirrorFreeBytes;
    }

    @Override
    public UsageOverview overview(UUID userId) {
        // Both daily pools reset on the same UTC-day boundary the trackers key on.
        Instant dailyResetAt = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        Usage global = ai.globalToday();
        Usage mine = ai.userToday(userId);
        UsageOverview.Ai aiMeter = new UsageOverview.Ai(
                ai.dailyNeuronLimit(), ai.perUserNeuronLimit(),
                round(global.neurons()), global.tokens(),
                round(mine.neurons()), mine.tokens());

        UsageOverview.Email emailMeter = new UsageOverview.Email(email.dailyLimit(), email.sentToday());

        long storageBytes = documents.sumAllSizeBytes();
        UsageOverview.Store storageMeter = new UsageOverview.Store(storageBytes, storageFreeBytes);

        Long dbBytes = jdbc.queryForObject("select pg_database_size(current_database())", Long.class);
        UsageOverview.Store databaseMeter = new UsageOverview.Store(dbBytes == null ? 0L : dbBytes, databaseFreeBytes);

        boolean mirrorEnabled = mirror.isEnabled();
        UsageOverview.Mirror mirrorMeter = new UsageOverview.Mirror(
                mirrorEnabled, mirrorEnabled ? storageBytes : 0L, mirrorFreeBytes);

        return new UsageOverview(dailyResetAt, aiMeter, emailMeter, storageMeter, databaseMeter, mirrorMeter);
    }

    private static double round(double n) {
        return Math.round(n * 100.0) / 100.0;
    }
}
