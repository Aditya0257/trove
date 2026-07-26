/*
 * ============================================================================
 *  Document — the core index row for one stored file
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  JPA entity mapping the `document` table (DESIGN.md §2, V3). It indexes a file
 *  that lives in object storage, plus the extracted/confirmed fields.
 *
 *  Business use case
 *  -----------------
 *  This is what every user-facing feature reads (list, spend, reminders, search).
 *  Crucially it is only an INDEX — the file + sidecar in object storage are the
 *  source of truth, and this row can be rebuilt from the sidecar if lost.
 *
 *  Solution architecture
 *  ---------------------
 *  Mutated in three moments: on upload (needs_review, extraction pending),
 *  after async extraction (fields + confidence filled), and on confirm (status +
 *  reviewer). space_id/uploaded_by/category_id/merchant_id are stored as plain UUID
 *  columns rather than JPA relations to keep the write path simple and explicit.
 *
 *  Design
 *  ------
 *  extraction_confidence stays NULL until extraction runs — the "pending" sentinel
 *  used by the reconciler (DECISIONS.md → D5). status defaults to needs_review.
 *  `extra` is real jsonb, mapped via @JdbcTypeCode(JSON). Timestamps are managed by
 *  Hibernate so INSERTs carry values instead of relying on DB defaults.
 *
 *  Reasoning & logic
 *  -----------------
 *  Fields that never change post-insert (keys, hash, size, uploader) have getters
 *  only; fields extraction/confirm update have setters. This encodes the lifecycle
 *  in the type.
 * ============================================================================
 */
package com.trove.entity;
import com.trove.enums.DocumentStatus;

import com.trove.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "document")
public class Document extends BaseEntity {

    // ── identity / ownership (immutable after insert) ─────────────────────────
    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    // ── storage pointers + file facts (immutable after insert) ────────────────
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "sidecar_key", nullable = false)
    private String sidecarKey;

    @Column(name = "file_hash", nullable = false)
    private String fileHash;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "original_filename")
    private String originalFilename;

    // ── extracted / editable fields ───────────────────────────────────────────
    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(name = "doc_date")
    private LocalDate docDate;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "currency")
    private String currency = "INR";

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "raw_text")
    private String rawText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra", nullable = false)
    private Map<String, Object> extra = new HashMap<>();

    @Column(name = "extraction_confidence")
    private BigDecimal extractionConfidence;

    // ── review lifecycle ──────────────────────────────────────────────────────
    @Column(name = "is_vital", nullable = false)
    private boolean vital = false;

    /** True when the stored file bytes are AES-encrypted at rest (vital documents). */
    @Column(name = "encrypted", nullable = false)
    private boolean encrypted = false;

    @Column(name = "status", nullable = false)
    private String status = DocumentStatus.NEEDS_REVIEW;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    // Soft-delete tombstone (status = 'deleted'): when it was trashed, by whom, and the
    // R2 key the live file was moved to. storage_key stays the ORIGINAL path so Restore
    // knows where to put the file back; trash_key is where the bytes actually live now.
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Column(name = "trash_key")
    private String trashKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Document() {
        // for JPA
    }

    /** Constructs a fresh upload row (status defaults to needs_review). */
    public Document(UUID spaceId, UUID uploadedBy, String storageKey, String sidecarKey,
                    String fileHash, String mimeType, long sizeBytes, String originalFilename,
                    UUID categoryId) {
        this.spaceId = spaceId;
        this.uploadedBy = uploadedBy;
        this.storageKey = storageKey;
        this.sidecarKey = sidecarKey;
        this.fileHash = fileHash;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.originalFilename = originalFilename;
        this.categoryId = categoryId;
    }

    /**
     * Reconstructs a document with a KNOWN id (disaster-recovery from a sidecar, or
     * faithful import). isNew stays true so save() inserts with this exact id. Line
     * items are not carried here (they are not part of the sidecar).
     */
    public static Document restore(UUID id, UUID spaceId, UUID uploadedBy, String storageKey,
                                   String sidecarKey, String fileHash, String mimeType, long sizeBytes,
                                   String originalFilename, UUID categoryId, UUID merchantId,
                                   java.time.LocalDate docDate, BigDecimal amount, String currency,
                                   java.time.LocalDate dueDate, String rawText, Map<String, Object> extra,
                                   BigDecimal extractionConfidence, boolean vital, boolean encrypted,
                                   String status) {
        Document d = new Document(spaceId, uploadedBy, storageKey, sidecarKey, fileHash, mimeType,
                sizeBytes, originalFilename, categoryId);
        d.setId(id);
        d.merchantId = merchantId;
        d.docDate = docDate;
        d.amount = amount;
        if (currency != null) d.currency = currency;
        d.dueDate = dueDate;
        d.rawText = rawText;
        if (extra != null) d.extra = extra;
        d.extractionConfidence = extractionConfidence;
        d.vital = vital;
        d.encrypted = encrypted;
        if (status != null) d.status = status;
        return d;
    }

    // ── getters (all fields) ──────────────────────────────────────────────────
    public UUID getSpaceId() { return spaceId; }
    public UUID getUploadedBy() { return uploadedBy; }
    public String getStorageKey() { return storageKey; }
    public String getSidecarKey() { return sidecarKey; }
    public String getFileHash() { return fileHash; }
    public String getMimeType() { return mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getOriginalFilename() { return originalFilename; }
    public UUID getCategoryId() { return categoryId; }
    public UUID getMerchantId() { return merchantId; }
    public LocalDate getDocDate() { return docDate; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public LocalDate getDueDate() { return dueDate; }
    public String getRawText() { return rawText; }
    public Map<String, Object> getExtra() { return extra; }
    public BigDecimal getExtractionConfidence() { return extractionConfidence; }
    public boolean isVital() { return vital; }
    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }
    public String getStatus() { return status; }
    public UUID getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public UUID getDeletedBy() { return deletedBy; }
    public void setDeletedBy(UUID deletedBy) { this.deletedBy = deletedBy; }
    public String getTrashKey() { return trashKey; }
    public void setTrashKey(String trashKey) { this.trashKey = trashKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // ── setters (only for fields the lifecycle mutates) ───────────────────────
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }
    public void setDocDate(LocalDate docDate) { this.docDate = docDate; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public void setRawText(String rawText) { this.rawText = rawText; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra; }
    public void setExtractionConfidence(BigDecimal extractionConfidence) { this.extractionConfidence = extractionConfidence; }
    public void setVital(boolean vital) { this.vital = vital; }
    public void setStatus(String status) { this.status = status; }
    public void setReviewedBy(UUID reviewedBy) { this.reviewedBy = reviewedBy; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
}
