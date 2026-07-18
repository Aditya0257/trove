/*
 * ============================================================================
 *  ExportService — builds the on-demand full export ZIP for a space
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Produces a single ZIP containing manifest.json (complete, LLM-readable records),
 *  data.csv (spreadsheet-friendly), and files/ (the original files + sidecars).
 *
 *  Business use case
 *  -----------------
 *  The ultimate "no provider outage can wipe me" guarantee (CLAUDE.md): a user can
 *  download everything and, later, upload it back to fully restore the system.
 *
 *  Solution architecture
 *  ---------------------
 *  Space-scoped (any member may export). Reads rows from the index and originals from
 *  object storage, streams them into an in-memory ZIP, and logs a backup_run.
 *
 *  Reasoning & logic
 *  -----------------
 *  files/ mirrors the object-store layout (files/<storageKey>, files/<sidecarKey>) so
 *  the archive is self-describing and import can restore keys faithfully. A missing
 *  object is skipped (not fatal) so a partial vault still exports.
 * ============================================================================
 */
package com.trove.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trove.category.Category;
import com.trove.category.CategoryRepository;
import com.trove.document.Document;
import com.trove.document.DocumentRepository;
import com.trove.document.LineItem;
import com.trove.document.LineItemRepository;
import com.trove.merchant.Merchant;
import com.trove.merchant.MerchantRepository;
import com.trove.space.SpaceAuthorization;
import com.trove.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final DocumentRepository documentRepository;
    private final LineItemRepository lineItemRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;
    private final StorageService storageService;
    private final SpaceAuthorization authorization;
    private final BackupRunService backupRunService;
    private final ObjectMapper objectMapper;

    public ExportService(DocumentRepository documentRepository, LineItemRepository lineItemRepository,
                         CategoryRepository categoryRepository, MerchantRepository merchantRepository,
                         StorageService storageService, SpaceAuthorization authorization,
                         BackupRunService backupRunService, ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.lineItemRepository = lineItemRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
        this.storageService = storageService;
        this.authorization = authorization;
        this.backupRunService = backupRunService;
        this.objectMapper = objectMapper;
    }

    /** Builds the export ZIP for a space (caller must be a member). */
    @Transactional(readOnly = true)
    public byte[] exportSpace(UUID spaceId, UUID userId) {
        authorization.requireCanRead(spaceId, userId);
        BackupRun run = backupRunService.start(BackupKind.EXPORT);
        try {
            Map<UUID, String> categoryCodes = categoryRepository.findAll().stream()
                    .collect(Collectors.toMap(Category::getId, Category::getCode));
            Map<UUID, String> merchantNames = merchantRepository.findAll().stream()
                    .collect(Collectors.toMap(Merchant::getId, Merchant::getCanonicalName));

            List<Document> docs = documentRepository.findBySpaceIdOrderByCreatedAtDesc(spaceId);

            List<Map<String, Object>> records = new ArrayList<>();
            for (Document d : docs) {
                records.add(toRecord(d, categoryCodes, merchantNames));
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("exportedAt", Instant.now().toString());
            manifest.put("spaceId", spaceId);
            manifest.put("documentCount", docs.size());
            manifest.put("documents", records);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(baos)) {
                writeEntry(zip, "manifest.json", objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(manifest));
                writeEntry(zip, "data.csv", buildCsv(records).getBytes(StandardCharsets.UTF_8));
                for (Document d : docs) {
                    copyObject(zip, d.getStorageKey());
                    copyObject(zip, d.getSidecarKey());
                }
            }
            byte[] bytes = baos.toByteArray();
            backupRunService.success(run, "zip:" + bytes.length + "B",
                    "documents=" + docs.size());
            log.info("Exported space {} — {} documents, {} bytes", spaceId, docs.size(), bytes.length);
            return bytes;
        } catch (Exception e) {
            backupRunService.fail(run, e.getMessage());
            throw new IllegalStateException("Export failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> toRecord(Document d, Map<UUID, String> categories, Map<UUID, String> merchants) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("documentId", d.getId());
        r.put("spaceId", d.getSpaceId());
        r.put("uploadedBy", d.getUploadedBy());
        r.put("storageKey", d.getStorageKey());
        r.put("sidecarKey", d.getSidecarKey());
        r.put("fileHash", d.getFileHash());
        r.put("mimeType", d.getMimeType());
        r.put("sizeBytes", d.getSizeBytes());
        r.put("originalFilename", d.getOriginalFilename());
        r.put("category", d.getCategoryId() != null ? categories.get(d.getCategoryId()) : null);
        r.put("merchant", d.getMerchantId() != null ? merchants.get(d.getMerchantId()) : null);
        r.put("docDate", d.getDocDate() != null ? d.getDocDate().toString() : null);
        r.put("amount", d.getAmount());
        r.put("currency", d.getCurrency());
        r.put("dueDate", d.getDueDate() != null ? d.getDueDate().toString() : null);
        r.put("status", d.getStatus());
        r.put("isVital", d.isVital());
        r.put("extractionConfidence", d.getExtractionConfidence());
        r.put("rawText", d.getRawText());
        r.put("extra", d.getExtra());
        r.put("createdAt", d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
        List<Map<String, Object>> items = new ArrayList<>();
        for (LineItem li : lineItemRepository.findByDocumentId(d.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("description", li.getDescription());
            m.put("quantity", li.getQuantity());
            m.put("unitPrice", li.getUnitPrice());
            m.put("amount", li.getAmount());
            items.add(m);
        }
        r.put("lineItems", items);
        return r;
    }

    private String buildCsv(List<Map<String, Object>> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("documentId,category,merchant,docDate,amount,currency,dueDate,status,storageKey,originalFilename\n");
        for (Map<String, Object> r : records) {
            sb.append(csv(r.get("documentId"))).append(',')
              .append(csv(r.get("category"))).append(',')
              .append(csv(r.get("merchant"))).append(',')
              .append(csv(r.get("docDate"))).append(',')
              .append(csv(r.get("amount"))).append(',')
              .append(csv(r.get("currency"))).append(',')
              .append(csv(r.get("dueDate"))).append(',')
              .append(csv(r.get("status"))).append(',')
              .append(csv(r.get("storageKey"))).append(',')
              .append(csv(r.get("originalFilename"))).append('\n');
        }
        return sb.toString();
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private void copyObject(ZipOutputStream zip, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            byte[] bytes = storageService.get(key);
            writeEntry(zip, "files/" + key, bytes);
        } catch (Exception e) {
            log.warn("Export: skipping missing object {} ({})", key, e.getMessage());
        }
    }

    private void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
