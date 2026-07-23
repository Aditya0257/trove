/*
 * ============================================================================
 *  EmbeddingService — builds, stores and searches document embeddings
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Turns a document into a compact text, embeds it (Cloudflare in prod, Stub offline),
 *  upserts the vector into document_embedding, and runs space-scoped similarity search.
 *
 *  Business use case
 *  -----------------
 *  The semantic half of "Ask your vault": find the documents most relevant to a
 *  question so the assistant can answer from them.
 *
 *  Solution architecture
 *  ---------------------
 *  pgvector via JdbcTemplate (JPA doesn't map the vector type). The active provider is
 *  chosen once: Cloudflare when an account+token exist, else the offline Stub. Indexing
 *  is idempotent (upsert keyed by document_id) and re-runs when the model changes.
 *
 *  Reasoning & logic
 *  -----------------
 *  The embedded text is the human-meaningful gist — category, merchant, date, amount,
 *  then the OCR raw text (truncated) — so semantic matches line up with how people ask.
 * ============================================================================
 */
package com.trove.chat;

import com.trove.category.CategoryRepository;
import com.trove.document.Document;
import com.trove.document.DocumentRepository;
import com.trove.merchant.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    /** Cap OCR text fed to the embedder — keeps token cost tiny and bounded. */
    private static final int MAX_TEXT_CHARS = 1500;

    private final EmbeddingProvider provider;
    private final JdbcTemplate jdbc;
    private final DocumentRepository documentRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;

    public EmbeddingService(CloudflareEmbeddingProvider cloudflare, StubEmbeddingProvider stub,
                            JdbcTemplate jdbc, DocumentRepository documentRepository,
                            CategoryRepository categoryRepository, MerchantRepository merchantRepository) {
        // Prefer the real provider when Cloudflare is configured; otherwise run offline.
        this.provider = cloudflare.isConfigured() ? cloudflare : stub;
        this.jdbc = jdbc;
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
        log.info("Embeddings using provider '{}' ({} dims)", provider.model(), provider.dimensions());
    }

    public String model() {
        return provider.model();
    }

    /** Embeds one document and upserts its vector. Best-effort: logs and returns false on failure. */
    public boolean index(UUID documentId, UUID billToUserId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            return false;
        }
        try {
            float[] vec = provider.embed(composeText(doc), billToUserId);
            jdbc.update("""
                    insert into document_embedding (document_id, space_id, embedding, model, updated_at)
                    values (?, ?, cast(? as vector), ?, now())
                    on conflict (document_id) do update
                       set embedding = excluded.embedding, model = excluded.model,
                           space_id = excluded.space_id, updated_at = now()
                    """, doc.getId(), doc.getSpaceId(), toVectorLiteral(vec), provider.model());
            return true;
        } catch (Exception e) {
            log.warn("Embedding index failed for document {} — {}", documentId, e.getMessage());
            return false;
        }
    }

    /** Retrieval hit: a document id and its cosine distance (smaller = closer). */
    public record Hit(UUID documentId, double distance) {
    }

    /** Top-k documents in a space most similar to the query text (excludes trashed docs). */
    public List<Hit> search(UUID spaceId, String queryText, UUID billToUserId, int k) {
        float[] q = provider.embed(queryText, billToUserId);
        return jdbc.query("""
                select e.document_id, (e.embedding <=> cast(? as vector)) as dist
                from document_embedding e
                join document d on d.id = e.document_id
                where e.space_id = ? and d.status <> 'deleted'
                order by dist asc
                limit ?
                """,
                (rs, i) -> new Hit(UUID.fromString(rs.getString("document_id")), rs.getDouble("dist")),
                toVectorLiteral(q), spaceId, k);
    }

    /** Ids of non-deleted documents in a space that lack a current-model embedding. */
    public List<UUID> staleDocumentIds(UUID spaceId, int limit) {
        return jdbc.query("""
                select d.id from document d
                left join document_embedding e on e.document_id = d.id and e.model = ?
                where d.space_id = ? and d.status <> 'deleted' and e.document_id is null
                limit ?
                """,
                (rs, i) -> UUID.fromString(rs.getString("id")),
                provider.model(), spaceId, limit);
    }

    /** Ids of non-deleted documents anywhere that lack a current-model embedding (sweep). */
    public List<UUID> staleDocumentIds(int limit) {
        return jdbc.query("""
                select d.id from document d
                left join document_embedding e on e.document_id = d.id and e.model = ?
                where d.status <> 'deleted' and e.document_id is null
                limit ?
                """,
                (rs, i) -> UUID.fromString(rs.getString("id")),
                provider.model(), limit);
    }

    /** Compact, human-meaningful text for a document (what we embed). */
    private String composeText(Document doc) {
        StringBuilder sb = new StringBuilder();
        String category = doc.getCategoryId() == null ? null
                : categoryRepository.findById(doc.getCategoryId()).map(c -> c.getLabel()).orElse(null);
        String merchant = doc.getMerchantId() == null ? null
                : merchantRepository.findById(doc.getMerchantId()).map(m -> m.getCanonicalName()).orElse(null);
        if (category != null) sb.append("Category: ").append(category).append('\n');
        if (merchant != null) sb.append("Merchant: ").append(merchant).append('\n');
        if (doc.getDocDate() != null) sb.append("Date: ").append(doc.getDocDate()).append('\n');
        if (doc.getAmount() != null) {
            sb.append("Amount: ").append(doc.getAmount());
            if (doc.getCurrency() != null) sb.append(' ').append(doc.getCurrency());
            sb.append('\n');
        }
        if (doc.getDueDate() != null) sb.append("Due: ").append(doc.getDueDate()).append('\n');
        if (doc.getOriginalFilename() != null) sb.append("File: ").append(doc.getOriginalFilename()).append('\n');
        if (doc.getRawText() != null && !doc.getRawText().isBlank()) {
            String raw = doc.getRawText().trim();
            sb.append(raw, 0, Math.min(raw.length(), MAX_TEXT_CHARS));
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? "(empty document)" : text;
    }

    /** pgvector text literal: [f1,f2,...]. */
    private String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
