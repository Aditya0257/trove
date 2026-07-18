/*
 * ============================================================================
 *  S3StorageService — S3-protocol implementation of StorageService (R2 / MinIO)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Concrete StorageService backed by any S3-compatible store. Owns the object key
 *  scheme, writes files + sidecars, ensures the bucket exists, and mints presigned
 *  view URLs.
 *
 *  Business use case
 *  -----------------
 *  Every document a user trusts to Trove lands here first and lives here forever.
 *  This class is the durable vault; the DB merely indexes what it holds.
 *
 *  Solution architecture
 *  ---------------------
 *  Named for the protocol, not a vendor: the same code runs against MinIO (dev) and
 *  Cloudflare R2 (prod), selected purely by StorageProperties (DECISIONS.md → D1).
 *  Uses AWS SDK v2 S3Client + S3Presigner built in S3Config.
 *
 *  Design
 *  ------
 *  Key scheme (DESIGN.md §6.1): {categoryCode}/{yyyy-MM}/{slug}-{shortId}.{ext}.
 *  Sidecar key = same path with .json. store() records the content hash so callers
 *  don't recompute it. On startup it optionally creates the bucket (dev convenience).
 *
 *  Reasoning & logic
 *  -----------------
 *  store() intentionally does NOT write the sidecar — the caller writes it once it
 *  has assembled the full document snapshot, guaranteeing sidecar == DB row.
 * ============================================================================
 */
package com.trove.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trove.common.HashUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class S3StorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties props;
    private final ObjectMapper objectMapper;

    public S3StorageService(S3Client s3, S3Presigner presigner,
                            StorageProperties props, ObjectMapper objectMapper) {
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * On startup, create the bucket if it is missing (dev convenience; harmless in
     * prod where the bucket already exists). Guarded by trove.storage.auto-create-bucket.
     */
    @PostConstruct
    void ensureBucketExists() {
        if (!props.isAutoCreateBucket()) {
            return;
        }
        String bucket = props.getBucket();
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Object storage bucket '{}' is present.", bucket);
        } catch (NoSuchBucketException e) {
            log.info("Bucket '{}' not found — creating it.", bucket);
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    @Override
    public StoredObject store(UUID spaceId, String categoryCode, MultipartFile file) {
        return storeBytes(spaceId, categoryCode, file.getOriginalFilename(),
                resolveContentType(file), readBytes(file));
    }

    @Override
    public StoredObject storeBytes(UUID spaceId, String categoryCode, String originalFilename,
                                   String contentType, byte[] bytes) {
        String ct = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
        String hash = HashUtil.sha256Hex(bytes);
        String storageKey = buildKey(categoryCode, originalFilename, ct);
        String sidecarKey = deriveSidecarKey(storageKey);

        s3.putObject(PutObjectRequest.builder()
                        .bucket(props.getBucket())
                        .key(storageKey)
                        .contentType(ct)
                        .build(),
                RequestBody.fromBytes(bytes));

        log.info("Stored object key={} size={}B mime={}", storageKey, bytes.length, ct);
        return new StoredObject(storageKey, sidecarKey, hash, bytes.length, ct);
    }

    @Override
    public void writeSidecar(String storageKey, DocumentSidecar sidecar) {
        String sidecarKey = deriveSidecarKey(storageKey);
        byte[] json = toJson(sidecar);
        s3.putObject(PutObjectRequest.builder()
                        .bucket(props.getBucket())
                        .key(sidecarKey)
                        .contentType("application/json")
                        .build(),
                RequestBody.fromBytes(json));
        log.debug("Wrote sidecar key={}", sidecarKey);
    }

    @Override
    public String presignedUrl(String storageKey, Duration ttl) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(props.getBucket())
                        .key(storageKey)
                        .build())
                .build();
        return presigner.presignGetObject(presignRequest).url().toString();
    }

    @Override
    public byte[] get(String storageKey) {
        return s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(props.getBucket())
                        .key(storageKey)
                        .build())
                .asByteArray();
    }

    @Override
    public void put(String storageKey, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(props.getBucket())
                        .key(storageKey)
                        .contentType(contentType != null ? contentType : "application/octet-stream")
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public List<String> list(String prefix) {
        List<String> keys = new ArrayList<>();
        ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                .bucket(props.getBucket())
                .prefix(prefix == null ? "" : prefix);
        ListObjectsV2Response resp;
        String token = null;
        do {
            resp = s3.listObjectsV2(req.continuationToken(token).build());
            resp.contents().forEach(o -> keys.add(o.key()));
            token = resp.isTruncated() ? resp.nextContinuationToken() : null;
        } while (token != null);
        return keys;
    }

    @Override
    public void delete(String storageKey) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(props.getBucket())
                .key(storageKey)
                .build());
        log.info("Deleted object key={}", storageKey);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Builds {categoryCode}/{yyyy-MM}/{slug}-{shortId}.{ext} for a fresh upload. */
    private String buildKey(String categoryCode, String originalFilename, String contentType) {
        String month = LocalDate.now(ZoneOffset.UTC).format(MONTH);
        String base = stripExtension(originalFilename);
        String slug = slugify(base);
        String shortId = UUID.randomUUID().toString().substring(0, 6);
        String ext = resolveExtension(originalFilename, contentType);
        String safeCategory = (categoryCode == null || categoryCode.isBlank()) ? "uncategorized" : categoryCode;
        return "%s/%s/%s-%s%s".formatted(safeCategory, month, slug, shortId, ext);
    }

    /** Sidecar key = storage key with its extension replaced by .json. */
    private String deriveSidecarKey(String storageKey) {
        int slash = storageKey.lastIndexOf('/');
        int dot = storageKey.lastIndexOf('.');
        if (dot > slash) {
            return storageKey.substring(0, dot) + ".json";
        }
        return storageKey + ".json";
    }

    private String slugify(String value) {
        if (value == null || value.isBlank()) {
            return "document";
        }
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return slug.isBlank() ? "document" : slug;
    }

    private String stripExtension(String filename) {
        if (filename == null) {
            return "document";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /** Prefer the filename's extension; otherwise map a few common mime types. */
    private String resolveExtension(String filename, String contentType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                return filename.substring(dot).toLowerCase(Locale.ROOT);
            }
        }
        if (contentType == null) {
            return "";
        }
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }

    private String resolveContentType(MultipartFile file) {
        String ct = file.getContentType();
        return (ct == null || ct.isBlank()) ? "application/octet-stream" : ct;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read uploaded file bytes", e);
        }
    }

    private byte[] toJson(DocumentSidecar sidecar) {
        try {
            return objectMapper.writeValueAsBytes(sidecar);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize sidecar JSON", e);
        }
    }
}
