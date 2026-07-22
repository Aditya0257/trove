/*
 * ============================================================================
 *  ExtractionWorker — the transactional unit of extraction work
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  For one document id: read the file from storage, run the active provider,
 *  resolve category + merchant, persist the extracted fields + line items, and
 *  rewrite the sidecar. This is the actual "read the document" step.
 *
 *  Business use case
 *  -----------------
 *  Turns a stored file into structured, reviewable fields — the product's core
 *  value. The document deliberately stays in needs_review; a human still confirms.
 *
 *  Solution architecture
 *  ---------------------
 *  Runs INSIDE the extraction executor thread (invoked by ExtractionDispatcher).
 *  Kept as its own bean so @Transactional applies when called cross-bean — this is
 *  a deliberate part of the async design (DECISIONS.md → D3). Idempotent, so both
 *  the AFTER_COMMIT listener and the crash reconciler can safely trigger it.
 *
 *  Reasoning & logic
 *  -----------------
 *  Idempotency guard: if extraction_confidence is already set, we skip — the row was
 *  already extracted (prevents duplicate work if event + reconciler both fire).
 *  Line items are cleared then re-written so a re-run never duplicates them.
 * ============================================================================
 */
package com.trove.extraction;

import com.trove.category.Category;
import com.trove.category.CategoryService;
import com.trove.document.Document;
import com.trove.document.DocumentRepository;
import com.trove.document.DocumentStatus;
import com.trove.document.LineItem;
import com.trove.document.LineItemRepository;
import com.trove.document.SidecarFactory;
import com.trove.extraction.engine.ExtractionEngine;
import com.trove.extraction.engine.ExtractionOutcome;
import com.trove.merchant.Merchant;
import com.trove.merchant.MerchantService;
import com.trove.storage.DocumentSidecar;
import com.trove.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class ExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);

    private final DocumentRepository documentRepository;
    private final LineItemRepository lineItemRepository;
    private final StorageService storageService;
    private final CategoryService categoryService;
    private final MerchantService merchantService;
    private final ExtractionEngine extractionEngine;

    public ExtractionWorker(DocumentRepository documentRepository,
                            LineItemRepository lineItemRepository,
                            StorageService storageService,
                            CategoryService categoryService,
                            MerchantService merchantService,
                            ExtractionEngine extractionEngine) {
        this.documentRepository = documentRepository;
        this.lineItemRepository = lineItemRepository;
        this.storageService = storageService;
        this.categoryService = categoryService;
        this.merchantService = merchantService;
        this.extractionEngine = extractionEngine;
    }

    /**
     * Extracts one document. Safe to call more than once (idempotent). Runs in its
     * own transaction so a failure rolls back cleanly and the reconciler retries.
     */
    @Transactional
    public void process(UUID documentId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            log.warn("Extraction skipped — document {} no longer exists", documentId);
            return;
        }
        if (doc.getExtractionConfidence() != null) {
            log.debug("Extraction skipped — document {} already extracted", documentId);
            return;
        }
        // Never overwrite a human-confirmed document. Besides being the right invariant
        // (the human's values are final), this lets flows that confirm quickly after
        // upload — e.g. filing email screenshots — set their own category/fields without
        // a late extraction run clobbering them.
        if (DocumentStatus.CONFIRMED.equals(doc.getStatus())) {
            log.debug("Extraction skipped — document {} already confirmed", documentId);
            return;
        }

        byte[] bytes = storageService.get(doc.getStorageKey());
        // The engine walks the configured provider fallback chain and returns the
        // first acceptable result (or a best-effort/stub result). See DECISIONS.md → D9.
        ExtractionOutcome outcome = extractionEngine.run(bytes, doc.getMimeType());
        ExtractionResult result = outcome.result();

        // Resolve category (always non-null) and merchant (optional).
        Category category = categoryService.resolve(doc.getSpaceId(), result.categoryCode());
        doc.setCategoryId(category.getId());

        Merchant merchant = merchantService.resolve(result.merchantName());
        doc.setMerchantId(merchant != null ? merchant.getId() : null);

        // Apply extracted fields. Numbers/dates are NOT trusted yet — the human
        // confirms them; we only record what the provider read.
        doc.setDocDate(result.docDate());
        doc.setAmount(result.amount());
        if (result.currency() != null && !result.currency().isBlank()) {
            doc.setCurrency(result.currency());
        }
        doc.setDueDate(result.dueDate());
        doc.setRawText(result.rawText());

        // Record extracted extras plus provenance: which provider/model actually read
        // this document, and whether it cleared the acceptance bar.
        Map<String, Object> extra = result.extra() != null ? new HashMap<>(result.extra()) : new HashMap<>();
        extra.put("extractionProvider", outcome.provider());
        if (outcome.model() != null) {
            extra.put("extractionModel", outcome.model());
        }
        extra.put("extractionAccepted", outcome.accepted());
        // Notice System (D23): the full chain trail + a derived two-channel notice, so
        // web/mobile can show "auto-fill paused for today" (or "review our read") with
        // the developer detail underneath. Rides into the sidecar → survives DB rebuild.
        com.trove.common.notice.ApiNotice notice = outcome.toNotice();
        java.util.Map<String, Object> meta = outcome.metaMap();
        java.util.Map<String, Object> noticeMap = new java.util.LinkedHashMap<>();
        noticeMap.put("level", notice.level().json());
        noticeMap.put("code", notice.code());
        noticeMap.put("userMessage", notice.userMessage());
        noticeMap.put("devNote", notice.devNote());
        meta.put("notice", noticeMap);
        extra.put("extractionMeta", meta);
        doc.setExtra(extra);
        doc.setExtractionConfidence(result.confidence());

        documentRepository.save(doc);

        // Rewrite line items idempotently.
        lineItemRepository.deleteByDocumentId(documentId);
        if (result.lineItems() != null) {
            result.lineItems().forEach(li -> lineItemRepository.save(
                    new LineItem(documentId, li.description(), li.quantity(), null, li.amount())));
        }

        // Keep the sidecar in lock-step with the row (self-describing bucket).
        DocumentSidecar sidecar = SidecarFactory.of(doc, category.getCode(),
                merchant != null ? merchant.getCanonicalName() : null);
        storageService.writeSidecar(doc.getStorageKey(), sidecar);

        log.info("Extracted document {} → category={} merchant={} confidence={}",
                documentId, category.getCode(),
                merchant != null ? merchant.getCanonicalName() : "(none)", result.confidence());
    }
}
