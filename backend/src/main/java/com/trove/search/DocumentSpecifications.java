/*
 * ============================================================================
 *  DocumentSpecifications — builds a JPA Specification from a SearchQuery
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Translates the optional filters in a SearchQuery (plus a resolved categoryId and
 *  matching merchant ids) into a composable JPA Specification over Document.
 *
 *  Business use case
 *  -----------------
 *  One place that assembles the WHERE clause for search, so structured and
 *  natural-language searches behave identically and safely (parameterized, no SQL
 *  injection).
 *
 *  Design
 *  ------
 *  Each filter is added only when present. Free text matches raw_text OR original
 *  filename OR a resolved merchant id — so "reliance" finds docs by that merchant or
 *  with the word in their text/filename.
 * ============================================================================
 */
package com.trove.search;

import com.trove.document.Document;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class DocumentSpecifications {

    private DocumentSpecifications() {
    }

    public static Specification<Document> build(UUID spaceId, SearchQuery q,
                                                UUID categoryId, List<UUID> textMerchantIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("spaceId"), spaceId));

            if (categoryId != null) {
                predicates.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (q.getStatus() != null && !q.getStatus().isBlank()) {
                predicates.add(cb.equal(root.get("status"), q.getStatus()));
            }
            if (q.getDateFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("docDate"), q.getDateFrom()));
            }
            if (q.getDateTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("docDate"), q.getDateTo()));
            }
            if (q.getAmountMin() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), q.getAmountMin()));
            }
            if (q.getAmountMax() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), q.getAmountMax()));
            }
            if (q.getText() != null && !q.getText().isBlank()) {
                String like = "%" + q.getText().toLowerCase(Locale.ROOT) + "%";
                List<Predicate> textOr = new ArrayList<>();
                textOr.add(cb.like(cb.lower(root.get("rawText")), like));
                textOr.add(cb.like(cb.lower(root.get("originalFilename")), like));
                if (textMerchantIds != null && !textMerchantIds.isEmpty()) {
                    textOr.add(root.get("merchantId").in(textMerchantIds));
                }
                predicates.add(cb.or(textOr.toArray(new Predicate[0])));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
