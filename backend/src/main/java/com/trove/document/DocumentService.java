/*
 * ============================================================================
 *  DocumentService — the core upload / list / confirm business logic
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Orchestrates Slice 1's vertical slice: upload (hash → dedupe → store → sidecar →
 *  row → trigger extraction), list by category, fetch one, and confirm a review.
 *
 *  Business use case
 *  -----------------
 *  This is the heart of "snap a document and Trove files it." It enforces the two
 *  non-negotiables: the object store is written first (source of truth), and nothing
 *  is final until a human confirms.
 *
 *  Solution architecture
 *  ---------------------
 *  Upload follows DESIGN.md §4.1: store the file + initial sidecar, insert the row
 *  as needs_review, then publish DocumentUploadedEvent. Extraction is triggered
 *  AFTER commit by the extraction package (DECISIONS.md → D3) — this service never
 *  calls the provider directly, keeping features decoupled. At upload the file is
 *  filed under the provisional "uncategorized" path; extraction corrects it (D4).
 *
 *  Reasoning & logic
 *  -----------------
 *  Dedupe uses the content hash within the space (brief). The hash is computed once
 *  here and re-derived identically by the storage layer (shared HashUtil). Responses
 *  resolve category CODE + merchant NAME and a presigned file URL for clients.
 * ============================================================================
 */
package com.trove.document;

import com.trove.category.Category;
import com.trove.category.CategoryRepository;
import com.trove.category.CategoryService;
import com.trove.common.HashUtil;
import com.trove.common.security.EncryptionService;
import com.trove.common.error.DuplicateDocumentException;
import com.trove.common.error.NotFoundException;
import com.trove.document.dto.ConfirmRequest;
import com.trove.document.dto.DocumentResponse;
import com.trove.document.dto.LineItemResponse;
import com.trove.anomaly.AnomalyResult;
import com.trove.anomaly.AnomalyService;
import com.trove.merchant.Merchant;
import com.trove.merchant.MerchantRepository;
import com.trove.merchant.MerchantService;
import com.trove.space.SpaceAuthorization;
import com.trove.storage.DocumentSidecar;
import com.trove.storage.StorageProperties;
import com.trove.storage.StorageService;
import com.trove.storage.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** File is stored here until extraction resolves the real category (D4). */
    private static final String PROVISIONAL_CATEGORY = "uncategorized";

    private final DocumentRepository documentRepository;
    private final LineItemRepository lineItemRepository;
    private final StorageService storageService;
    private final StorageProperties storageProperties;
    private final CategoryService categoryService;
    private final CategoryRepository categoryRepository;
    private final MerchantService merchantService;
    private final MerchantRepository merchantRepository;
    private final SpaceAuthorization spaceAuthorization;
    private final AnomalyService anomalyService;
    private final EncryptionService encryptionService;
    private final ApplicationEventPublisher events;

    public DocumentService(DocumentRepository documentRepository,
                           LineItemRepository lineItemRepository,
                           StorageService storageService,
                           StorageProperties storageProperties,
                           CategoryService categoryService,
                           CategoryRepository categoryRepository,
                           MerchantService merchantService,
                           MerchantRepository merchantRepository,
                           SpaceAuthorization spaceAuthorization,
                           AnomalyService anomalyService,
                           EncryptionService encryptionService,
                           ApplicationEventPublisher events) {
        this.documentRepository = documentRepository;
        this.lineItemRepository = lineItemRepository;
        this.storageService = storageService;
        this.storageProperties = storageProperties;
        this.categoryService = categoryService;
        this.categoryRepository = categoryRepository;
        this.merchantService = merchantService;
        this.merchantRepository = merchantRepository;
        this.spaceAuthorization = spaceAuthorization;
        this.anomalyService = anomalyService;
        this.encryptionService = encryptionService;
        this.events = events;
    }

    /**
     * Uploads one document. Order matters: the durable object store is written
     * before the DB row, and extraction is triggered only after commit.
     */
    /** Upload without a vital flag (forwarded/ingested docs default to non-vital). */
    @Transactional
    public DocumentResponse upload(UUID spaceId, UUID uploadedBy, MultipartFile file) {
        return upload(spaceId, uploadedBy, file, false);
    }

    /**
     * Uploads a document. When {@code vital} is true the stored file bytes are
     * AES-encrypted at rest (passport/ID/policy) and served via the decrypt-stream
     * endpoint instead of a presigned URL.
     */
    @Transactional
    public DocumentResponse upload(UUID spaceId, UUID uploadedBy, MultipartFile file, boolean vital) {
        return upload(spaceId, uploadedBy, file, vital, true);
    }

    /**
     * Uploads a document. When {@code extract} is false the AI reading step is skipped
     * (the doc is stored and left in needs_review for manual entry) — used when a user
     * turns AI reading off to avoid the wait and the credit cost.
     */
    @Transactional
    public DocumentResponse upload(UUID spaceId, UUID uploadedBy, MultipartFile file, boolean vital, boolean extract) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        // 0) Authorize: the uploader must be an owner/member of the target space.
        spaceAuthorization.requireCanWrite(spaceId, uploadedBy);

        // 1) Hash the PLAINTEXT bytes and reject a duplicate already in this space
        //    (dedupe is on content, independent of whether we encrypt at rest).
        byte[] bytes = readBytes(file);
        String hash = HashUtil.sha256Hex(bytes);
        documentRepository.findBySpaceIdAndFileHashAndStatusNot(spaceId, hash, DocumentStatus.DELETED)
                .ifPresent(existing -> {
                    throw new DuplicateDocumentException(existing.getId());
                });

        // 2) Store under the provisional category path. Vital docs are encrypted first.
        Category provisional = categoryService.resolve(spaceId, PROVISIONAL_CATEGORY);
        String contentType = (file.getContentType() != null && !file.getContentType().isBlank())
                ? file.getContentType() : "application/octet-stream";
        byte[] toStore = vital ? encryptionService.encryptBytes(bytes) : bytes;
        StoredObject stored = storageService.storeBytes(spaceId, provisional.getCode(),
                file.getOriginalFilename(), contentType, toStore);

        // 3) Insert the index row as needs_review. file_hash/size_bytes describe the
        //    PLAINTEXT (so dedupe + display are stable regardless of encryption).
        Document doc = new Document(spaceId, uploadedBy, stored.storageKey(), stored.sidecarKey(),
                hash, contentType, bytes.length, file.getOriginalFilename(), provisional.getId());
        doc.setVital(vital);
        doc.setEncrypted(vital);
        documentRepository.saveAndFlush(doc);

        // 4) Write the initial sidecar so the bucket is self-describing immediately.
        DocumentSidecar sidecar = SidecarFactory.of(doc, provisional.getCode(), null);
        storageService.writeSidecar(doc.getStorageKey(), sidecar);

        // 5) After this transaction commits, extraction is dispatched (see
        //    ExtractionEventListener). We never call the provider inline. When AI reading
        //    is turned off, skip it and mark the doc so the UI shows the form immediately
        //    instead of waiting for a read that will never come.
        if (extract) {
            events.publishEvent(new DocumentUploadedEvent(doc.getId()));
            log.info("Uploaded document {} into space {} (status=needs_review)", doc.getId(), spaceId);
        } else {
            doc.setExtra(java.util.Map.of("extractionSkipped", true));
            documentRepository.save(doc);
            log.info("Uploaded document {} into space {} (status=needs_review, AI reading OFF)", doc.getId(), spaceId);
        }
        return toResponse(doc);
    }

    /** Lists documents in a space, optionally filtered to one category code. */
    @Transactional(readOnly = true)
    public List<DocumentResponse> list(UUID spaceId, UUID userId, String categoryCode) {
        spaceAuthorization.requireCanRead(spaceId, userId);
        List<Document> docs;
        if (categoryCode != null && !categoryCode.isBlank()) {
            Category category = categoryService.resolve(spaceId, categoryCode);
            docs = documentRepository.findBySpaceIdAndCategoryIdAndStatusNotOrderByCreatedAtDesc(
                    spaceId, category.getId(), DocumentStatus.DELETED);
        } else {
            docs = documentRepository.findBySpaceIdAndStatusNotOrderByCreatedAtDesc(spaceId, DocumentStatus.DELETED);
        }
        return docs.stream().map(this::toResponse).toList();
    }

    /**
     * Returns the document's file bytes for viewing/download, decrypting if the file
     * is stored encrypted (vital). Enforces space membership.
     */
    @Transactional(readOnly = true)
    public DownloadedFile content(UUID documentId, UUID userId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        spaceAuthorization.requireCanRead(doc.getSpaceId(), userId);
        byte[] bytes = storageService.get(doc.getStorageKey());
        if (doc.isEncrypted()) {
            bytes = encryptionService.decryptBytes(bytes);
        }
        return new DownloadedFile(bytes, doc.getMimeType(), doc.getOriginalFilename());
    }

    /** Re-encrypts or decrypts the stored file so it matches the document's vital flag. */
    private void syncEncryptionWithVital(Document doc) {
        if (doc.isVital() && !doc.isEncrypted()) {
            byte[] plain = storageService.get(doc.getStorageKey());
            storageService.put(doc.getStorageKey(), encryptionService.encryptBytes(plain), doc.getMimeType());
            doc.setEncrypted(true);
        } else if (!doc.isVital() && doc.isEncrypted()) {
            byte[] cipher = storageService.get(doc.getStorageKey());
            storageService.put(doc.getStorageKey(), encryptionService.decryptBytes(cipher), doc.getMimeType());
            doc.setEncrypted(false);
        }
    }

    /** File bytes + metadata for a download/stream response. */
    public record DownloadedFile(byte[] bytes, String contentType, String filename) {
    }

    /** Lists confirmed documents flagged as spending anomalies in a space. */
    @Transactional(readOnly = true)
    public List<DocumentResponse> listAnomalies(UUID spaceId, UUID userId) {
        spaceAuthorization.requireCanRead(spaceId, userId);
        return documentRepository.findAnomalies(spaceId).stream().map(this::toResponse).toList();
    }

    /** Fetches a single document by id (404 if unknown, 403 if not a member). */
    @Transactional(readOnly = true)
    public DocumentResponse get(UUID documentId, UUID userId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        spaceAuthorization.requireCanRead(doc.getSpaceId(), userId);
        return toResponse(doc);
    }

    /**
     * Soft-deletes a document: moves its file + sidecar from the live path to a trash
     * prefix in object storage (NOT erased), and marks the row status=deleted with who/
     * when. The document disappears from every normal list/spend/search but stays fully
     * recoverable via {@link #restore} until the retention window elapses and the purge
     * sweep removes it for good. Honours the core principle: deletion is never a silent,
     * irreversible wipe. Requires write access.
     */
    @Transactional
    public void delete(UUID documentId, UUID userId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        spaceAuthorization.requireCanWrite(doc.getSpaceId(), userId);
        if (DocumentStatus.DELETED.equals(doc.getStatus())) {
            return;   // already in the trash
        }

        // Move the live objects into _trash/{space}/{doc}/ — keep storage_key pointing at
        // the ORIGINAL path so restore knows the destination; trash_key is where bytes are now.
        String trashPrefix = "_trash/" + doc.getSpaceId() + "/" + doc.getId() + "/";
        String trashKey = trashPrefix + basename(doc.getStorageKey());
        moveObject(doc.getStorageKey(), trashKey, doc.getMimeType());
        if (doc.getSidecarKey() != null && !doc.getSidecarKey().isBlank()) {
            moveObject(doc.getSidecarKey(), trashPrefix + basename(doc.getSidecarKey()), "application/json");
        }

        doc.setTrashKey(trashKey);
        doc.setDeletedBy(userId);
        doc.setDeletedAt(Instant.now());
        doc.setStatus(DocumentStatus.DELETED);
        documentRepository.save(doc);
        events.publishEvent(new DocumentTrashedEvent(doc.getId(), doc.getSpaceId()));
        log.info("Trashed document {} in space {} (recoverable until purge)", documentId, doc.getSpaceId());
    }

    /** The trash view: soft-deleted documents in a space, most recently deleted first. */
    @Transactional(readOnly = true)
    public List<DocumentResponse> listTrash(UUID spaceId, UUID userId) {
        spaceAuthorization.requireCanRead(spaceId, userId);
        return documentRepository.findBySpaceIdAndStatusOrderByDeletedAtDesc(spaceId, DocumentStatus.DELETED)
                .stream().map(this::toResponse).toList();
    }

    /**
     * Restores a trashed document: moves its file + sidecar back to the live path and
     * clears the tombstone. Prior review state is inferred — a document that had been
     * confirmed (has a reviewer) returns to confirmed, otherwise to needs_review.
     */
    @Transactional
    public void restore(UUID documentId, UUID userId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        spaceAuthorization.requireCanWrite(doc.getSpaceId(), userId);
        if (!DocumentStatus.DELETED.equals(doc.getStatus())) {
            return;
        }
        if (doc.getTrashKey() != null) {
            String trashPrefix = "_trash/" + doc.getSpaceId() + "/" + doc.getId() + "/";
            moveObject(doc.getTrashKey(), doc.getStorageKey(), doc.getMimeType());
            if (doc.getSidecarKey() != null && !doc.getSidecarKey().isBlank()) {
                moveObject(trashPrefix + basename(doc.getSidecarKey()), doc.getSidecarKey(), "application/json");
            }
        }
        doc.setTrashKey(null);
        doc.setDeletedAt(null);
        doc.setDeletedBy(null);
        doc.setStatus(doc.getReviewedAt() != null ? DocumentStatus.CONFIRMED : DocumentStatus.NEEDS_REVIEW);
        documentRepository.save(doc);
        events.publishEvent(new DocumentRestoredEvent(doc.getId(), doc.getSpaceId()));
        log.info("Restored document {} in space {}", documentId, doc.getSpaceId());
    }

    /**
     * Purges a trashed document for good: deletes its trashed file + sidecar from object
     * storage, then the index row (line items + drive sync rows cascade). The purge event
     * fires BEFORE the row is removed — synchronously — so a listener can still read the
     * drive_sync rows and delete the Drive copies. The independent B2 mirror is left as an
     * append-only archival backstop and is not touched here.
     */
    @Transactional
    public void purge(Document doc) {
        String trashPrefix = "_trash/" + doc.getSpaceId() + "/" + doc.getId() + "/";
        if (doc.getTrashKey() != null) {
            deleteQuietly(doc.getTrashKey());
            if (doc.getSidecarKey() != null && !doc.getSidecarKey().isBlank()) {
                deleteQuietly(trashPrefix + basename(doc.getSidecarKey()));
            }
        } else {
            // Defensive: if it was never trashed, clear whatever is at the live keys.
            deleteQuietly(doc.getStorageKey());
            if (doc.getSidecarKey() != null && !doc.getSidecarKey().isBlank()) {
                deleteQuietly(doc.getSidecarKey());
            }
        }
        // Fire first (sync rows still present), then remove the row (cascades line items + sync).
        events.publishEvent(new DocumentPurgedEvent(doc.getId(), doc.getSpaceId()));
        lineItemRepository.deleteByDocumentId(doc.getId());
        documentRepository.delete(doc);
        log.info("Purged document {} from space {} (removed from live storage + DB)", doc.getId(), doc.getSpaceId());
    }

    /** Immediate purge of one trashed document by id (owner-driven "delete forever"). */
    @Transactional
    public void purgeNow(UUID documentId, UUID userId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        spaceAuthorization.requireCanWrite(doc.getSpaceId(), userId);
        if (!DocumentStatus.DELETED.equals(doc.getStatus())) {
            throw new IllegalStateException("Only a trashed document can be purged");
        }
        purge(doc);
    }

    /** Purges every trashed document whose retention window has elapsed (scheduled sweep). */
    public int purgeExpired(int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, java.time.temporal.ChronoUnit.DAYS);
        List<Document> expired = documentRepository.findByStatusAndDeletedAtBefore(DocumentStatus.DELETED, cutoff);
        for (Document doc : expired) {
            try {
                purge(doc);
            } catch (Exception e) {
                log.warn("Could not purge expired document {} - {}", doc.getId(), e.getMessage());
            }
        }
        if (!expired.isEmpty()) {
            log.info("Purge sweep removed {} document(s) past {}-day retention", expired.size(), retentionDays);
        }
        return expired.size();
    }

    /** Moves a stored object from one key to another (copy bytes, then delete the source).
     *  Best-effort on the delete half so a stale source never blocks the logical move. */
    private void moveObject(String fromKey, String toKey, String contentType) {
        if (fromKey == null || fromKey.equals(toKey)) {
            return;
        }
        byte[] bytes = storageService.get(fromKey);
        storageService.put(toKey, bytes, contentType != null ? contentType : "application/octet-stream");
        deleteQuietly(fromKey);
    }

    /** Last path segment of a storage key (filename). */
    private String basename(String key) {
        if (key == null) {
            return "";
        }
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    /**
     * Deletes the object-storage files (original + sidecar) for every document in a
     * space. Used when deleting the whole space: the DB rows are removed by the
     * space's ON DELETE CASCADE, but the objects live outside the DB and must be
     * purged here first. Best-effort per object; never throws.
     */
    @Transactional(readOnly = true)
    public void purgeStorageForSpace(UUID spaceId) {
        List<Document> docs = documentRepository.findBySpaceIdOrderByCreatedAtDesc(spaceId);
        for (Document doc : docs) {
            deleteQuietly(doc.getStorageKey());
            if (doc.getSidecarKey() != null && !doc.getSidecarKey().isBlank()) {
                deleteQuietly(doc.getSidecarKey());
            }
        }
        log.info("Purged storage for {} document(s) in space {}", docs.size(), spaceId);
    }

    /** Best-effort object delete: a storage hiccup shouldn't block removing the row. */
    private void deleteQuietly(String storageKey) {
        try {
            storageService.delete(storageKey);
        } catch (Exception e) {
            log.warn("Could not delete object {} (removing index row anyway): {}", storageKey, e.getMessage());
        }
    }

    /**
     * Confirms a document: applies any reviewer edits, sets status=confirmed +
     * reviewer/at, and rewrites the sidecar. This is the human-in-the-loop gate.
     */
    @Transactional
    public DocumentResponse confirm(UUID documentId, UUID reviewerId, ConfirmRequest req) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
        spaceAuthorization.requireCanWrite(doc.getSpaceId(), reviewerId);

        if (req != null) {
            if (req.category() != null && !req.category().isBlank()) {
                doc.setCategoryId(categoryService.resolve(doc.getSpaceId(), req.category()).getId());
            }
            if (req.merchant() != null && !req.merchant().isBlank()) {
                Merchant m = merchantService.resolve(req.merchant());
                doc.setMerchantId(m != null ? m.getId() : null);
            }
            if (req.docDate() != null) doc.setDocDate(req.docDate());
            if (req.amount() != null) doc.setAmount(req.amount());
            if (req.currency() != null && !req.currency().isBlank()) doc.setCurrency(req.currency());
            if (req.dueDate() != null) doc.setDueDate(req.dueDate());
            if (req.vital() != null) doc.setVital(req.vital());
            if (req.extra() != null) doc.setExtra(req.extra());
        }

        // If the vital flag changed, (de)encrypt the stored file bytes to match.
        syncEncryptionWithVital(doc);

        doc.setStatus(DocumentStatus.CONFIRMED);
        doc.setReviewedBy(reviewerId);
        doc.setReviewedAt(Instant.now());

        // Anomaly check against the trailing average for this category (confirmed
        // history). The verdict is stored on the document so clients can surface
        // "higher than usual" without recomputing. See DECISIONS.md → D13.
        AnomalyResult anomaly = anomalyService.evaluate(doc.getSpaceId(), doc.getCategoryId(),
                doc.getAmount(), doc.getId(), doc.getDocDate());
        Map<String, Object> extra = doc.getExtra() != null ? new HashMap<>(doc.getExtra()) : new HashMap<>();
        extra.put("anomaly", anomaly.toMap());
        doc.setExtra(extra);

        // Flush now so @UpdateTimestamp is refreshed before we rewrite the sidecar
        // and build the response.
        documentRepository.saveAndFlush(doc);

        // Keep the durable sidecar in step with the confirmed row.
        String categoryCode = categoryCodeOf(doc.getCategoryId());
        String merchantName = merchantNameOf(doc.getMerchantId());
        storageService.writeSidecar(doc.getStorageKey(),
                SidecarFactory.of(doc, categoryCode, merchantName));

        // After commit, the reminder feature turns a confirmed due date into a
        // 'due' reminder (decoupled via event). See ReminderEventListener.
        events.publishEvent(new DocumentConfirmedEvent(doc.getId(), doc.getSpaceId(), doc.getDueDate()));

        log.info("Confirmed document {} by reviewer {}", documentId, reviewerId);
        return toResponse(doc);
    }

    /**
     * Maps already-loaded documents to responses (presigned URLs + line items).
     * Used by search, which does its own authorization and query. Read-only tx so
     * lazy access and presigning happen within a session.
     */
    @Transactional(readOnly = true)
    public List<DocumentResponse> present(List<Document> docs) {
        return docs.stream().map(this::toResponse).toList();
    }

    // ── mapping helpers ───────────────────────────────────────────────────────

    private DocumentResponse toResponse(Document doc) {
        String categoryCode = categoryCodeOf(doc.getCategoryId());
        String merchantName = merchantNameOf(doc.getMerchantId());
        // Encrypted (vital) files can't be handed out as presigned URLs — the client
        // would get ciphertext — so they are served via the decrypt-stream endpoint.
        String fileUrl = doc.isEncrypted()
                ? "/api/documents/" + doc.getId() + "/content"
                : storageService.presignedUrl(doc.getStorageKey(),
                        Duration.ofSeconds(storageProperties.getPresignTtlSeconds()));
        List<LineItemResponse> items = lineItemRepository.findByDocumentId(doc.getId()).stream()
                .map(li -> new LineItemResponse(li.getDescription(), li.getQuantity(),
                        li.getUnitPrice(), li.getAmount()))
                .toList();

        return new DocumentResponse(
                doc.getId(), doc.getSpaceId(), doc.getUploadedBy(),
                doc.getStorageKey(), doc.getSidecarKey(), doc.getFileHash(),
                doc.getMimeType(), doc.getSizeBytes(), doc.getOriginalFilename(),
                categoryCode, merchantName,
                doc.getDocDate(), doc.getAmount(), doc.getCurrency(), doc.getDueDate(),
                doc.getRawText(), doc.getExtra(), doc.getExtractionConfidence(),
                doc.isVital(), doc.isEncrypted(), doc.getStatus(), doc.getReviewedBy(), doc.getReviewedAt(),
                doc.getDeletedAt(), doc.getCreatedAt(), doc.getUpdatedAt(), fileUrl, items);
    }

    private String categoryCodeOf(UUID categoryId) {
        if (categoryId == null) return null;
        return categoryRepository.findById(categoryId).map(Category::getCode).orElse(null);
    }

    private String merchantNameOf(UUID merchantId) {
        if (merchantId == null) return null;
        return merchantRepository.findById(merchantId).map(Merchant::getCanonicalName).orElse(null);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read uploaded file bytes", e);
        }
    }
}
