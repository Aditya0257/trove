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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /** Duplicate detection: same content hash already stored in this space. */
    Optional<Document> findBySpaceIdAndFileHash(UUID spaceId, String fileHash);

    /** All documents in a space, newest first. */
    List<Document> findBySpaceIdOrderByCreatedAtDesc(UUID spaceId);

    /** Documents in a space filed under a specific category, newest first. */
    List<Document> findBySpaceIdAndCategoryIdOrderByCreatedAtDesc(UUID spaceId, UUID categoryId);

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
