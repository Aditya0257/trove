/*
 * ============================================================================
 *  DocumentRepository — data access for documents
 * ============================================================================
 *  Purpose:        persistence + the queries Slice 1 needs (dedupe, list by
 *                  category, find pending extractions).
 *  Business use:    dedupe (same bytes in a space), "list by category", and the
 *                  crash-recovery sweep all live here.
 *  Design:         Spring Data derived queries. The "pending extraction" query uses
 *                  extraction_confidence IS NULL as the sentinel (DECISIONS.md → D5).
 * ============================================================================
 */
package com.trove.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID>,
        JpaSpecificationExecutor<Document> {

    /** Duplicate detection: same content hash already LIVE in this space (a trashed copy
     *  must not block re-uploading the same file). */
    Optional<Document> findBySpaceIdAndFileHashAndStatusNot(UUID spaceId, String fileHash, String status);

    /** All documents in a space regardless of status (used by whole-space storage purge). */
    List<Document> findBySpaceIdOrderByCreatedAtDesc(UUID spaceId);

    /** Live documents in a space (excludes trashed), newest first. */
    List<Document> findBySpaceIdAndStatusNotOrderByCreatedAtDesc(UUID spaceId, String status);

    /** Live documents in a space under a category (excludes trashed), newest first. */
    List<Document> findBySpaceIdAndCategoryIdAndStatusNotOrderByCreatedAtDesc(
            UUID spaceId, UUID categoryId, String status);

    /** One page of live documents in a space (excludes trashed). The Pageable carries the
     *  sort (newest first, id as a stable tiebreaker) so offset paging is deterministic. */
    Page<Document> findBySpaceIdAndStatusNot(UUID spaceId, String status, Pageable pageable);

    /** One page of live documents in a space under a category (excludes trashed). */
    Page<Document> findBySpaceIdAndCategoryIdAndStatusNot(
            UUID spaceId, UUID categoryId, String status, Pageable pageable);

    /** One page of live documents in a space, excluding the "email" category (those belong to
     *  Mail, not Documents). Null-safe so an uncategorised document is still included. */
    @Query("""
            select d from Document d
            where d.spaceId = :spaceId and d.status <> :status
              and (d.categoryId is null or d.categoryId not in
                   (select c.id from com.trove.category.Category c where c.code = 'email'))
            """)
    Page<Document> findLiveExcludingEmail(@Param("spaceId") UUID spaceId, @Param("status") String status, Pageable pageable);

    /** Every live document in a space excluding the "email" category, newest first (the
     *  unpaged "show all" path for browser-find / export). */
    @Query("""
            select d from Document d
            where d.spaceId = :spaceId and d.status <> :status
              and (d.categoryId is null or d.categoryId not in
                   (select c.id from com.trove.category.Category c where c.code = 'email'))
            order by d.createdAt desc
            """)
    List<Document> findLiveExcludingEmail(@Param("spaceId") UUID spaceId, @Param("status") String status);

    /** Trashed documents in a space, most recently deleted first (the Trash view). */
    List<Document> findBySpaceIdAndStatusOrderByDeletedAtDesc(UUID spaceId, String status);

    /** Documents for one merchant in a space with a given status, oldest first - used to spot a
     *  regular billing cadence (a likely subscription) from the gaps between their dates. */
    List<Document> findBySpaceIdAndMerchantIdAndStatusOrderByDocDateAsc(
            UUID spaceId, UUID merchantId, String status);

    /** Trashed documents past their retention window (the purge sweep). */
    List<Document> findByStatusAndDeletedAtBefore(String status, java.time.Instant cutoff);

    /** Documents whose extraction never completed (crash-recovery sweep). */
    List<Document> findByExtractionConfidenceIsNull();

    /** Confirmed documents flagged as anomalous (extra.anomaly.anomaly == true). */
    @Query(value = """
            select * from document
            where space_id = :spaceId
              and status = 'confirmed'
              and (extra -> 'anomaly' ->> 'anomaly') = 'true'
            order by updated_at desc
            """, nativeQuery = true)
    List<Document> findAnomalies(@Param("spaceId") UUID spaceId);
}
