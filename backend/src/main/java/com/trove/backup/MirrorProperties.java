/*
 * ============================================================================
 *  MirrorProperties — the independent second-cloud object-storage target
 * ============================================================================
 *  Purpose:        binds trove.mirror.* — a second S3-compatible bucket (e.g.
 *                  Backblaze B2) that the primary vault is copied to.
 *  Business use:    Tier-2 of the backup story: an INDEPENDENT cloud copy, so a
 *                  single provider outage/account loss can't wipe the vault.
 *  Design:         Backblaze B2 is S3-compatible, so the mirror reuses the same S3
 *                  SDK — only endpoint/keys/bucket differ. Disabled by default.
 * ============================================================================
 */
package com.trove.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.mirror")
public class MirrorProperties {

    private boolean enabled = false;
    private String endpoint;
    private String region = "us-east-1";
    private String accessKey;
    private String secretKey;
    private String bucket;
    private boolean pathStyleAccess = true;

    public boolean configured() {
        return enabled && endpoint != null && !endpoint.isBlank()
                && accessKey != null && !accessKey.isBlank()
                && bucket != null && !bucket.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

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
}
