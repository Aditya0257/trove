/*
 * ============================================================================
 *  CategoryService — resolves a category code to a category row
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Turns an extracted category code (e.g. "electricity") into the concrete
 *  Category row to store on a document, preferring a space-custom category over the
 *  global one, and falling back to "uncategorized" when the code is unknown.
 *
 *  Business use case
 *  -----------------
 *  Extraction returns a free-form code; documents must still file cleanly even if a
 *  provider invents a code we don't have. The fallback guarantees every document
 *  lands somewhere sensible.
 *
 *  Solution architecture
 *  ---------------------
 *  Used by DocumentService (provisional "uncategorized" at upload) and by the
 *  ExtractionWorker (real category after extraction). See DECISIONS.md → D4.
 *
 *  Reasoning & logic
 *  -----------------
 *  Space-specific first, then global, then the global "uncategorized" seeded in V6.
 *  If even that is missing, we fail loudly — the taxonomy seed is a hard invariant.
 * ============================================================================
 */
package com.trove.service.impl;
import com.trove.entity.Category;
import com.trove.repository.CategoryRepository;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class CategoryService {

    private static final String FALLBACK_CODE = "uncategorized";

    private final CategoryRepository repository;

    public CategoryService(CategoryRepository repository) {
        this.repository = repository;
    }

    /**
     * Resolves a code to a Category for the given space: space-specific → global →
     * "uncategorized". Never returns null.
     */
    public Category resolve(UUID spaceId, String code) {
        if (code != null && !code.isBlank()) {
            if (spaceId != null) {
                var spaceSpecific = repository.findBySpaceIdAndCode(spaceId, code);
                if (spaceSpecific.isPresent()) {
                    return spaceSpecific.get();
                }
            }
            var global = repository.findBySpaceIdIsNullAndCode(code);
            if (global.isPresent()) {
                return global.get();
            }
        }
        return repository.findBySpaceIdIsNullAndCode(FALLBACK_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "Fallback category '" + FALLBACK_CODE + "' is missing - check Flyway seed V6."));
    }

    /**
     * Finds a category by code without falling back (space-specific → global). Used by
     * search, where an unknown code should mean "no match", not the default category.
     */
    public Optional<Category> find(UUID spaceId, String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        if (spaceId != null) {
            var spaceSpecific = repository.findBySpaceIdAndCode(spaceId, code);
            if (spaceSpecific.isPresent()) {
                return spaceSpecific;
            }
        }
        return repository.findBySpaceIdIsNullAndCode(code);
    }
}
