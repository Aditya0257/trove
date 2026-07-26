/*
 * ============================================================================
 *  IntegrityService — verifies the "three copies, zero data loss" promise
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Checks that every document a space holds actually exists across the backup tiers —
 *  the live object + its self-describing sidecar in R2, a copy in the B2 mirror, and a
 *  copy in Google Drive — and reports any gaps, ranked by how dangerous they are.
 *
 *  Business use case
 *  -----------------
 *  The core principle is "the data is not disposable". Rather than trusting that, this
 *  continuously proves it: a dashboard (and a daily job) that turns "we keep three
 *  copies" into a verified, drift-detecting health report.
 *
 *  Solution architecture
 *  ---------------------
 *  One listing of R2 (and, if configured, B2) is pulled into a set; each document's keys
 *  are membership-checked against it — O(docs) with two list calls, cheap at this scale.
 *  Drive coverage comes from the per-document sync records. Orphans (objects with no DB
 *  row) are the reverse check: they show the DB is a rebuildable index, and orphaned
 *  sidecars are exactly what a rebuild would read back in.
 *
 *  Reasoning & logic
 *  -----------------
 *  A missing PRIMARY object is the only true data-loss risk → CRITICAL. A missing
 *  sidecar, mirror, or Drive copy is a redundancy gap → WARNING (and "not in Drive" is
 *  only a gap once the space actually has a Drive linked). Trash lives under _trash/ and
 *  is excluded from orphan analysis — it's intentional, transient state.
 * ============================================================================
 */
package com.trove.service.impl;

import com.trove.service.impl.MirrorService;
import com.trove.repository.CategoryRepository;
import com.trove.entity.Document;
import com.trove.repository.DocumentRepository;
import com.trove.enums.DocumentStatus;
import com.trove.dto.IntegrityDtos.IntegrityReport;
import com.trove.dto.IntegrityDtos.Issue;
import com.trove.dto.IntegrityDtos.StorageIntegrity;
import com.trove.repository.MerchantRepository;
import com.trove.integration.StorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class IntegrityService {

    private static final String TRASH_PREFIX = "_trash/";

    private final DocumentRepository documentRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;
    private final StorageService storageService;
    private final MirrorService mirrorService;
    private final JdbcTemplate jdbc;

    public IntegrityService(DocumentRepository documentRepository, CategoryRepository categoryRepository,
                            MerchantRepository merchantRepository, StorageService storageService,
                            MirrorService mirrorService, JdbcTemplate jdbc) {
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
        this.storageService = storageService;
        this.mirrorService = mirrorService;
        this.jdbc = jdbc;
    }

    /** Verifies every live document in a space across the backup tiers. */
    public IntegrityReport report(UUID spaceId) {
        List<Document> docs = documentRepository
                .findBySpaceIdAndStatusNotOrderByCreatedAtDesc(spaceId, DocumentStatus.DELETED);

        Set<String> r2 = new HashSet<>(storageService.list(""));
        boolean mirrorEnabled = mirrorService.isEnabled();
        Set<String> mirror = mirrorEnabled ? mirrorService.listMirrorKeys() : Set.of();
        Set<UUID> driveSynced = driveSyncedIds(spaceId);
        boolean spaceHasDrive = jdbc.queryForObject(
                "select count(*) from drive_connection where space_id = ?", Integer.class, spaceId) > 0;

        int primaryOk = 0;
        int sidecarOk = 0;
        int mirrorOk = 0;
        int driveOk = 0;
        int critical = 0;
        List<Issue> issues = new ArrayList<>();

        for (Document doc : docs) {
            boolean hasPrimary = doc.getStorageKey() != null && r2.contains(doc.getStorageKey());
            boolean hasSidecar = doc.getSidecarKey() != null && r2.contains(doc.getSidecarKey());
            boolean hasMirror = mirrorEnabled && doc.getStorageKey() != null && mirror.contains(doc.getStorageKey());
            boolean hasDrive = driveSynced.contains(doc.getId());

            if (hasPrimary) primaryOk++;
            if (hasSidecar) sidecarOk++;
            if (hasMirror) mirrorOk++;
            if (hasDrive) driveOk++;

            String title = titleOf(doc);
            if (!hasPrimary) {
                issues.add(new Issue(doc.getId().toString(), title, "critical", "Live file missing from R2"));
                critical++;
            }
            if (!hasSidecar) {
                issues.add(new Issue(doc.getId().toString(), title, "warning", "Sidecar JSON missing from R2"));
            }
            if (mirrorEnabled && !hasMirror) {
                issues.add(new Issue(doc.getId().toString(), title, "warning", "Not yet copied to the B2 mirror"));
            }
            if (spaceHasDrive && !hasDrive) {
                issues.add(new Issue(doc.getId().toString(), title, "warning", "Not yet synced to Google Drive"));
            }
        }
        // Most dangerous first.
        issues.sort((a, b) -> rank(a.severity()) - rank(b.severity()));

        return new IntegrityReport(spaceId.toString(), Instant.now(), docs.size(),
                primaryOk, sidecarOk, mirrorEnabled ? mirrorOk : null, driveOk, critical,
                issues, storageIntegrity(r2, mirror, mirrorEnabled));
    }

    /** Global object-store view: total objects, indexed keys, and orphans (no DB row). */
    private StorageIntegrity storageIntegrity(Set<String> r2, Set<String> mirror, boolean mirrorEnabled) {
        // Every key any document references (across all spaces): live + sidecar + trashed.
        Set<String> known = new HashSet<>();
        jdbc.query("select storage_key, sidecar_key, trash_key from document", rs -> {
            addIf(known, rs.getString("storage_key"));
            addIf(known, rs.getString("sidecar_key"));
            addIf(known, rs.getString("trash_key"));
        });
        long orphans = 0;
        long rebuildable = 0;
        long live = 0;   // objects outside the trash
        for (String key : r2) {
            if (key.startsWith(TRASH_PREFIX)) {
                continue;   // intentional, transient - not an orphan
            }
            live++;
            if (!known.contains(key)) {
                orphans++;
                if (key.endsWith(".json")) {
                    rebuildable++;   // an orphaned sidecar is exactly what a rebuild reads
                }
            }
        }
        return new StorageIntegrity(live, known.size(), orphans, rebuildable, mirrorEnabled,
                mirrorEnabled ? mirror.size() : 0);
    }

    /** A vault-wide check for the scheduled job: how many live documents are missing their
     *  primary object or sidecar, and how many orphan objects exist. One R2 listing. */
    public GlobalCheck globalCheck() {
        Set<String> r2 = new HashSet<>(storageService.list(""));
        Set<String> known = new HashSet<>();
        jdbc.query("select storage_key, sidecar_key, trash_key from document", rs -> {
            addIf(known, rs.getString("storage_key"));
            addIf(known, rs.getString("sidecar_key"));
            addIf(known, rs.getString("trash_key"));
        });
        List<Document> docs = documentRepository.findAll();
        int missingPrimary = 0;
        int missingSidecar = 0;
        int live = 0;
        for (Document doc : docs) {
            if (DocumentStatus.DELETED.equals(doc.getStatus())) {
                continue;
            }
            live++;
            if (doc.getStorageKey() == null || !r2.contains(doc.getStorageKey())) {
                missingPrimary++;
            }
            if (doc.getSidecarKey() == null || !r2.contains(doc.getSidecarKey())) {
                missingSidecar++;
            }
        }
        long orphans = r2.stream().filter(k -> !k.startsWith(TRASH_PREFIX) && !known.contains(k)).count();
        return new GlobalCheck(live, missingPrimary, missingSidecar, orphans);
    }

    /** Vault-wide integrity summary (scheduled-job view). */
    public record GlobalCheck(int liveDocuments, int missingPrimary, int missingSidecar, long orphanObjects) {
    }

    private Set<UUID> driveSyncedIds(UUID spaceId) {
        List<UUID> ids = jdbc.query("""
                select distinct s.document_id
                from document_sync s
                join document d on d.id = s.document_id
                where d.space_id = ? and d.status <> 'deleted'
                """, (rs, i) -> UUID.fromString(rs.getString("document_id")), spaceId);
        return new HashSet<>(ids);
    }

    private String titleOf(Document doc) {
        String merchant = doc.getMerchantId() == null ? null
                : merchantRepository.findById(doc.getMerchantId()).map(m -> m.getCanonicalName()).orElse(null);
        if (merchant != null) {
            return merchant;
        }
        if (doc.getOriginalFilename() != null) {
            return doc.getOriginalFilename();
        }
        String code = doc.getCategoryId() == null ? null
                : categoryRepository.findById(doc.getCategoryId()).map(c -> c.getLabel()).orElse(null);
        return code != null ? code : "Document";
    }

    private void addIf(Set<String> set, String v) {
        if (v != null && !v.isBlank()) {
            set.add(v);
        }
    }

    private int rank(String severity) {
        return "critical".equals(severity) ? 0 : "warning".equals(severity) ? 1 : 2;
    }
}
