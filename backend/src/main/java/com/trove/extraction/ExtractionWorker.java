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
import java.util.List;
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
    private final AiUsageTracker aiUsage;

    public ExtractionWorker(DocumentRepository documentRepository,
                            LineItemRepository lineItemRepository,
                            StorageService storageService,
                            CategoryService categoryService,
                            MerchantService merchantService,
                            ExtractionEngine extractionEngine,
                            AiUsageTracker aiUsage) {
        this.documentRepository = documentRepository;
        this.lineItemRepository = lineItemRepository;
        this.storageService = storageService;
        this.categoryService = categoryService;
        this.merchantService = merchantService;
        this.extractionEngine = extractionEngine;
        this.aiUsage = aiUsage;
    }

    /**
     * Extracts one document. Safe to call more than once (idempotent). Runs in its
     * own transaction so a failure rolls back cleanly and the reconciler retries.
     */
    @Transactional
    public void process(UUID documentId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            log.warn("Extraction skipped - document {} no longer exists", documentId);
            return;
        }
        if (doc.getExtractionConfidence() != null) {
            log.debug("Extraction skipped - document {} already extracted", documentId);
            return;
        }
        // Never overwrite a human-confirmed document. Besides being the right invariant
        // (the human's values are final), this lets flows that confirm quickly after
        // upload — e.g. filing email screenshots — set their own category/fields without
        // a late extraction run clobbering them.
        if (DocumentStatus.CONFIRMED.equals(doc.getStatus())) {
            log.debug("Extraction skipped - document {} already confirmed", documentId);
            return;
        }

        byte[] bytes = storageService.get(doc.getStorageKey());
        // Gate billed extraction on the daily AI budget (shared ceiling + this user's
        // slice). When it's spent, the engine skips the paid providers and the free stub
        // reads the document instead — the upload still lands in needs_review.
        String block = aiUsage.blockReason(doc.getUploadedBy());
        if (block != null) {
            log.info("AI extraction budget reached for document {} - {}", documentId, block);
        }
        // The engine walks the configured provider fallback chain and returns the
        // first acceptable result (or a best-effort/stub result). See DECISIONS.md → D9.
        ExtractionOutcome outcome = extractionEngine.run(bytes, doc.getMimeType(), block == null);
        ExtractionResult result = outcome.result();

        // Bill the AI usage to the uploader + the app-wide total (shared Workers AI account).
        aiUsage.record(doc.getUploadedBy(), outcome.totalNeurons(), outcome.totalTokens());

        // A transient failure (e.g. the vision model timed out under load) that fell back
        // to the empty stub is NOT finalised: leaving extraction_confidence NULL lets the
        // reconciler re-dispatch this document on its next sweep, so a one-off blip
        // self-heals instead of permanently leaving the document blank. (Budget/quota
        // stubs are finalised normally - retrying them before the daily reset is pointless.)
        if (outcome.isRetryableStub()) {
            log.warn("Extraction for document {} failed transiently; leaving it un-finalised for the reconciler to retry",
                    documentId);
            return;
        }

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

        // Clipboard pastes land as "image.png" and screenshots as "Screenshot ….png". When
        // we actually read something useful, give the file a meaningful name derived from the
        // merchant (or category) + date — shown in the list AND used as the Drive filename.
        // Cosmetic only: storage_key is unchanged, and the human can still rename on review.
        String derived = deriveFilename(doc.getOriginalFilename(), doc.getMimeType(),
                merchant != null ? merchant.getCanonicalName() : result.merchantName(),
                category, doc.getDocDate());
        if (derived != null) {
            doc.setOriginalFilename(derived);
        }

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

    // Filename prefixes that mean "no real name" — clipboard pastes, screenshots, camera rolls.
    private static final List<String> GENERIC_NAME_PREFIXES = List.of(
            "image", "img", "unnamed", "clipboard", "pasted", "paste", "screenshot", "screen shot");

    /**
     * A meaningful filename derived from what we read, or null to keep the original.
     * Only kicks in when the uploaded name is generic AND we have a merchant or a real
     * (non-uncategorized) category, so a stub/empty read never renames anything.
     */
    private String deriveFilename(String current, String mimeType, String merchantName,
                                  Category category, java.time.LocalDate docDate) {
        if (!isGenericName(current)) {
            return null;
        }
        String label = merchantName != null && !merchantName.isBlank() ? merchantName
                : (category != null && !"uncategorized".equals(category.getCode()) ? category.getLabel() : null);
        if (label == null || label.isBlank()) {
            return null;
        }
        String base = slug(label);
        if (base.isEmpty()) {
            return null;
        }
        if (base.length() > 48) {
            base = base.substring(0, 48).replaceAll("-+$", "");
        }
        if (docDate != null) {
            base = base + "-" + docDate;   // ISO yyyy-MM-dd
        }
        return base + extensionOf(current, mimeType);
    }

    private boolean isGenericName(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String lower = name.trim().toLowerCase();
        return GENERIC_NAME_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    /** Lowercase, non-alphanumeric runs to single hyphens, trimmed of edge hyphens. */
    private String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }

    /** Extension from the original name, else inferred from the MIME type, else empty. */
    private String extensionOf(String filename, String mimeType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot > 0 && dot < filename.length() - 1) {
                return "." + filename.substring(dot + 1).toLowerCase();
            }
        }
        if (mimeType == null) {
            return "";
        }
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/heic" -> ".heic";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }
}
