/*
 * ============================================================================
 *  HashUtil — SHA-256 hashing for content-addressable duplicate detection
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Computes the SHA-256 hex digest of file bytes, used as document.file_hash.
 *
 *  Business use case
 *  -----------------
 *  People re-forward the same bill twice. The brief wants duplicate detection per
 *  space; a content hash makes "same bytes" cheap to detect regardless of filename.
 *
 *  Solution architecture
 *  ---------------------
 *  Shared by DocumentService (pre-store dedupe check) and S3StorageService (records
 *  the hash on the StoredObject). Using ONE implementation guarantees both agree.
 *
 *  Reasoning & logic
 *  -----------------
 *  SHA-256 is collision-resistant enough for dedupe and matches the "sha256:" shape
 *  used in the sidecar (DESIGN.md §6.1). Returned as lowercase hex.
 * ============================================================================
 */
package com.trove.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HashUtil {

    private HashUtil() {
    }

    /** Returns the lowercase hex SHA-256 of the given bytes. */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; this cannot happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
