/*
 * ============================================================================
 *  StorageService — the object-storage abstraction (load-bearing interface)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Abstracts durable object storage so the rest of the app never knows or cares
 *  whether it is talking to MinIO (dev) or Cloudflare R2 (prod). It owns the
 *  key/path scheme and ALWAYS writes a sidecar JSON next to every file.
 *
 *  Business use case
 *  -----------------
 *  This is the boundary that guarantees the core principle — files + sidecars in
 *  object storage are the single source of truth; the DB is a rebuildable index.
 *
 *  Solution architecture
 *  ---------------------
 *  One of the two load-bearing interfaces in DESIGN.md §6.1 (the other is
 *  ExtractionProvider). Implemented by S3StorageService (DECISIONS.md → D1).
 *  Swapping storage backends means a new impl, nothing else changes.
 *
 *  Design
 *  ------
 *  Method contract is copied from DESIGN.md §6.1. Key scheme:
 *  {categoryCode}/{yyyy-MM}/{slug}-{shortId}.{ext}. Sidecar key = same path .json.
 *
 *  Reasoning & logic
 *  -----------------
 *  store() takes the categoryCode explicitly because the caller decides filing; in
 *  Slice 1 that is the provisional "uncategorized" until extraction runs (D4).
 * ============================================================================
 */
package com.trove.storage;

import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface StorageService {

    /**
     * Stores the raw file under {categoryCode}/{yyyy-MM}/{slug}-{shortId}.{ext} and
     * returns the keys + hash/size/mime. Does NOT write the sidecar — the caller
     * writes it via writeSidecar once it knows the document id and fields, so the
     * sidecar and DB row are built from the same snapshot.
     */
    StoredObject store(UUID spaceId, String categoryCode, MultipartFile file);

    /**
     * Writes/overwrites the sidecar JSON for a stored file. The sidecar key is the
     * storage key with a .json extension. Called on upload, after extraction, and
     * after confirm so the bucket always mirrors the latest known state.
     */
    void writeSidecar(String storageKey, DocumentSidecar sidecar);

    /** Returns a short-lived signed URL a client can use to view/download the file. */
    String presignedUrl(String storageKey, Duration ttl);

    /** Fetches the raw bytes of a stored object (used by extraction to read the file). */
    byte[] get(String storageKey);

    /** Lists every object key under the given prefix ("" = whole bucket). Used by
     *  export and disaster-recovery rebuild to enumerate files + sidecars. */
    List<String> list(String prefix);

    /** Writes bytes at an exact key (used by import/restore to reinstate objects). */
    void put(String storageKey, byte[] bytes, String contentType);

    /** Deletes a single stored object by key. */
    void delete(String storageKey);
}
