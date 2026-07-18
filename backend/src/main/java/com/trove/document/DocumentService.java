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
import com.trove.common.error.DuplicateDocumentException;
import com.trove.common.error.NotFoundException;
import com.trove.document.dto.ConfirmRequest;
import com.trove.document.dto.DocumentResponse;
import com.trove.document.dto.LineItemResponse;
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
import java.util.List;
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
        this.events = events;
    }

    /**
     * Uploads one document. Order matters: the durable object store is written
     * before the DB row, and extraction is triggered only after commit.
     */
    @Transactional
    public DocumentResponse upload(UUID spaceId, UUID uploadedBy, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        // 0) Authorize: the uploader must be an owner/member of the target space.
        spaceAuthorization.requireCanWrite(spaceId, uploadedBy);

        // 1) Hash the bytes and reject a duplicate already in this space.
        byte[] bytes = readBytes(file);
        String hash = HashUtil.sha256Hex(bytes);
        documentRepository.findBySpaceIdAndFileHash(spaceId, hash).ifPresent(existing -> {
            throw new DuplicateDocumentException(existing.getId());
        });

        // 2) Store the file under the provisional category path (source of truth).
        Category provisional = categoryService.resolve(spaceId, PROVISIONAL_CATEGORY);
        StoredObject stored = storageService.store(spaceId, provisional.getCode(), file);

        // 3) Insert the index row as needs_review (extraction_confidence stays NULL).
        //    saveAndFlush forces the INSERT now so @CreationTimestamp/@UpdateTimestamp
        //    are populated before we build the sidecar and response (otherwise they
        //    would flush only at commit, leaving createdAt null in the first sidecar).
        Document doc = new Document(spaceId, uploadedBy, stored.storageKey(), stored.sidecarKey(),
                stored.fileHash(), stored.mimeType(), stored.sizeBytes(),
                file.getOriginalFilename(), provisional.getId());
        documentRepository.saveAndFlush(doc);

        // 4) Write the initial sidecar so the bucket is self-describing immediately.
        DocumentSidecar sidecar = SidecarFactory.of(doc, provisional.getCode(), null);
        storageService.writeSidecar(doc.getStorageKey(), sidecar);

        // 5) After this transaction commits, extraction is dispatched (see
        //    ExtractionEventListener). We never call the provider inline.
        events.publishEvent(new DocumentUploadedEvent(doc.getId()));

        log.info("Uploaded document {} into space {} (status=needs_review)", doc.getId(), spaceId);
        return toResponse(doc);
    }

    /** Lists documents in a space, optionally filtered to one category code. */
    @Transactional(readOnly = true)
    public List<DocumentResponse> list(UUID spaceId, UUID userId, String categoryCode) {
        spaceAuthorization.requireCanRead(spaceId, userId);
        List<Document> docs;
        if (categoryCode != null && !categoryCode.isBlank()) {
            Category category = categoryService.resolve(spaceId, categoryCode);
            docs = documentRepository.findBySpaceIdAndCategoryIdOrderByCreatedAtDesc(spaceId, category.getId());
        } else {
            docs = documentRepository.findBySpaceIdOrderByCreatedAtDesc(spaceId);
        }
        return docs.stream().map(this::toResponse).toList();
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

        doc.setStatus(DocumentStatus.CONFIRMED);
        doc.setReviewedBy(reviewerId);
        doc.setReviewedAt(Instant.now());
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

    // ── mapping helpers ───────────────────────────────────────────────────────

    private DocumentResponse toResponse(Document doc) {
        String categoryCode = categoryCodeOf(doc.getCategoryId());
        String merchantName = merchantNameOf(doc.getMerchantId());
        String fileUrl = storageService.presignedUrl(doc.getStorageKey(),
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
                doc.isVital(), doc.getStatus(), doc.getReviewedBy(), doc.getReviewedAt(),
                doc.getCreatedAt(), doc.getUpdatedAt(), fileUrl, items);
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
