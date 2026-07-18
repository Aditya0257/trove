/*
 * ============================================================================
 *  MirrorService — copies the primary vault to an independent second cloud
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Copies every object in the primary bucket (files + sidecars) to a second
 *  S3-compatible bucket (e.g. Backblaze B2), skipping objects already mirrored.
 *
 *  Business use case
 *  -----------------
 *  Tier-2 independent cloud copy: a different provider/account holds a full copy, so
 *  losing the primary provider still can't lose documents (CLAUDE.md core principle).
 *
 *  Solution architecture
 *  ---------------------
 *  Reuses the AWS S3 SDK against the mirror endpoint (B2 speaks S3). Reads from the
 *  primary via StorageService; writes with a lazily-built mirror S3 client. Diffs by
 *  key-listing so re-runs only copy new/missing objects. Logs a backup_run.
 *
 *  Reasoning & logic
 *  -----------------
 *  Key-listing diff is idempotent and cheap at this scale (thousands of small
 *  objects). The mirror client is built only when configured, so the feature is a
 *  no-op (and injects nothing extra) until trove.mirror.* is set.
 * ============================================================================
 */
package com.trove.backup;

import com.trove.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class MirrorService {

    private static final Logger log = LoggerFactory.getLogger(MirrorService.class);

    private final StorageService storageService;
    private final MirrorProperties props;
    private final BackupRunService backupRunService;
    private volatile S3Client mirrorClient;

    public MirrorService(StorageService storageService, MirrorProperties props,
                         BackupRunService backupRunService) {
        this.storageService = storageService;
        this.props = props;
        this.backupRunService = backupRunService;
    }

    public boolean isEnabled() {
        return props.configured();
    }

    /** Copies every primary object not already present in the mirror bucket. */
    public MirrorSummary mirror() {
        if (!props.configured()) {
            throw new IllegalStateException("Mirror is not configured (set trove.mirror.*)");
        }
        BackupRun run = backupRunService.start(BackupKind.MIRROR);
        int copied = 0;
        int skipped = 0;
        try {
            S3Client mirror = client();
            ensureBucket(mirror);
            Set<String> existing = listKeys(mirror);
            for (String key : storageService.list("")) {
                if (existing.contains(key)) {
                    skipped++;
                    continue;
                }
                byte[] bytes = storageService.get(key);
                mirror.putObject(PutObjectRequest.builder()
                                .bucket(props.getBucket()).key(key).build(),
                        RequestBody.fromBytes(bytes));
                copied++;
            }
            backupRunService.success(run, "mirror:" + props.getBucket(),
                    "copied=" + copied + " skipped=" + skipped);
            log.info("Mirror complete — copied={} skipped={} to bucket {}", copied, skipped, props.getBucket());
            return new MirrorSummary(copied, skipped);
        } catch (Exception e) {
            backupRunService.fail(run, e.getMessage());
            throw new IllegalStateException("Mirror failed: " + e.getMessage(), e);
        }
    }

    private S3Client client() {
        if (mirrorClient == null) {
            synchronized (this) {
                if (mirrorClient == null) {
                    mirrorClient = S3Client.builder()
                            .endpointOverride(URI.create(props.getEndpoint()))
                            .region(Region.of(props.getRegion()))
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                            .serviceConfiguration(S3Configuration.builder()
                                    .pathStyleAccessEnabled(props.isPathStyleAccess()).build())
                            .build();
                }
            }
        }
        return mirrorClient;
    }

    private void ensureBucket(S3Client mirror) {
        try {
            mirror.headBucket(HeadBucketRequest.builder().bucket(props.getBucket()).build());
        } catch (NoSuchBucketException e) {
            mirror.createBucket(CreateBucketRequest.builder().bucket(props.getBucket()).build());
        }
    }

    private Set<String> listKeys(S3Client mirror) {
        Set<String> keys = new HashSet<>();
        String token = null;
        do {
            ListObjectsV2Response resp = mirror.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(props.getBucket()).continuationToken(token).build());
            resp.contents().forEach(o -> keys.add(o.key()));
            token = resp.isTruncated() ? resp.nextContinuationToken() : null;
        } while (token != null);
        return keys;
    }

    public record MirrorSummary(int copied, int skipped) {
    }
}
