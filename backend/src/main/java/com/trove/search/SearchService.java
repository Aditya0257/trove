/*
 * ============================================================================
 *  SearchService — structured + natural-language document search
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Runs a SearchQuery against a space's documents, and provides a natural-language
 *  entry point that first parses free text into a SearchQuery.
 *
 *  Business use case
 *  -----------------
 *  "my last water bill", "all Nike purchases", "toll receipts from June" — find the
 *  right document fast, the way people actually think about their records.
 *
 *  Solution architecture
 *  ---------------------
 *  Authorizes the caller (any member may read), resolves the category code and any
 *  text→merchant matches to ids, builds a JPA Specification, and pages/sorts (newest
 *  first). Mapping to responses is delegated to DocumentService.present.
 *
 *  Reasoning & logic
 *  -----------------
 *  An unknown category code yields no results (rather than the fallback category), so
 *  "water" with no water docs returns empty instead of everything. Results are capped
 *  by the query's limit (1 for "last", up to 200 for "all", else 50).
 * ============================================================================
 */
package com.trove.search;

import com.trove.category.Category;
import com.trove.category.CategoryService;
import com.trove.document.Document;
import com.trove.document.DocumentRepository;
import com.trove.document.DocumentService;
import com.trove.document.dto.DocumentResponse;
import com.trove.merchant.Merchant;
import com.trove.merchant.MerchantRepository;
import com.trove.space.SpaceAuthorization;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SearchService {

    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final SpaceAuthorization authorization;
    private final CategoryService categoryService;
    private final MerchantRepository merchantRepository;
    private final NaturalQueryParser parser;

    public SearchService(DocumentRepository documentRepository, DocumentService documentService,
                         SpaceAuthorization authorization, CategoryService categoryService,
                         MerchantRepository merchantRepository, NaturalQueryParser parser) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.authorization = authorization;
        this.categoryService = categoryService;
        this.merchantRepository = merchantRepository;
        this.parser = parser;
    }

    /** Parses natural-language text into filters, runs the search, echoes both. */
    @Transactional(readOnly = true)
    public SearchResult naturalSearch(UUID spaceId, UUID userId, String queryText) {
        SearchQuery q = parser.parse(queryText);
        List<DocumentResponse> results = search(spaceId, userId, q);
        return new SearchResult(q, results.size(), results);
    }

    /** Runs a structured SearchQuery. */
    @Transactional(readOnly = true)
    public List<DocumentResponse> search(UUID spaceId, UUID userId, SearchQuery q) {
        authorization.requireCanRead(spaceId, userId);

        UUID categoryId = null;
        if (q.getCategoryCode() != null && !q.getCategoryCode().isBlank()) {
            Optional<Category> cat = categoryService.find(spaceId, q.getCategoryCode());
            if (cat.isEmpty()) {
                return List.of(); // unknown category → no matches
            }
            categoryId = cat.get().getId();
        }

        List<UUID> textMerchantIds = List.of();
        if (q.getText() != null && !q.getText().isBlank()) {
            textMerchantIds = merchantRepository.findByCanonicalNameContainingIgnoreCase(q.getText())
                    .stream().map(Merchant::getId).toList();
        }

        int limit = q.getLimit() != null ? Math.min(q.getLimit(), 200) : 50;
        Sort sort = Sort.by(Sort.Order.desc("docDate"), Sort.Order.desc("createdAt"));
        Specification<Document> spec = DocumentSpecifications.build(spaceId, q, categoryId, textMerchantIds);

        List<Document> docs = documentRepository.findAll(spec, PageRequest.of(0, limit, sort)).getContent();
        return documentService.present(docs);
    }

    /** A natural-language search result: the interpreted filters + the matches. */
    public record SearchResult(SearchQuery interpreted, int count, List<DocumentResponse> results) {
    }
}
