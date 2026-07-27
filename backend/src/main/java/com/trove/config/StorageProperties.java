/*
 * ============================================================================
 *  StorageProperties — object-storage configuration (R2 / MinIO)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Binds trove.storage.* from application.yml: endpoint, region, credentials,
 *  bucket, path-style flag, bucket auto-create, and presign TTL.
 *
 *  Business use case
 *  -----------------
 *  Object storage is the ONE source of truth in Trove. Everything about how we
 *  reach it must be configurable so the exact same code targets local MinIO in dev
 *  and Cloudflare R2 in prod — swapping only these values (DECISIONS.md → D1).
 *
 *  Solution architecture
 *  ---------------------
 *  Consumed by S3Config (to build the S3Client + S3Presigner) and by
 *  S3StorageService (bucket name, TTL, auto-create behavior).
 *
 *  Reasoning & logic
 *  -----------------
 *  path-style-access is true because MinIO requires bucket-in-path addressing; R2
 *  supports it too, so one setting works for both.
 * ============================================================================
 */
package com.trove.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.storage")
public class StorageProperties {

    private String endpoint;
    private String region;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private boolean pathStyleAccess = true;
    private boolean autoCreateBucket = true;
    private long presignTtlSeconds = 900;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public boolean isPathStyleAccess() { return pathStyleAccess; }
    public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }

    public boolean isAutoCreateBucket() { return autoCreateBucket; }
    public void setAutoCreateBucket(boolean autoCreateBucket) { this.autoCreateBucket = autoCreateBucket; }

    public long getPresignTtlSeconds() { return presignTtlSeconds; }
    public void setPresignTtlSeconds(long presignTtlSeconds) { this.presignTtlSeconds = presignTtlSeconds; }
}
