/*
 * ============================================================================
 *  S3Config — builds the S3 client + presigner for R2 / MinIO
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Creates the two Spring beans the storage layer needs: an S3Client (put/get/
 *  delete objects) and an S3Presigner (short-lived signed view URLs), both pointed
 *  at the configured S3-compatible endpoint.
 *
 *  Business use case
 *  -----------------
 *  Object storage holds the only durable copy of every document. This config is
 *  the seam that lets one codebase talk to MinIO locally and Cloudflare R2 in prod
 *  by changing configuration alone (DECISIONS.md → D1).
 *
 *  Solution architecture
 *  ---------------------
 *  Reads StorageProperties. Uses a static credentials provider (keys from env in
 *  prod) and forces path-style addressing (required by MinIO, supported by R2).
 *  The endpoint override is what makes the AWS SDK talk to a non-AWS backend.
 *
 *  Reasoning & logic
 *  -----------------
 *  Beans are singletons — the SDK client is thread-safe and expensive to build, so
 *  we create one and reuse it. Region is required by the SDK even for R2/MinIO, so
 *  we pass whatever is configured (us-east-1 by default).
 * ============================================================================
 */
package com.trove.config;

import com.trove.config.StorageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {

    private final StorageProperties props;

    public S3Config(StorageProperties props) {
        this.props = props;
    }

    /**
     * The main object-storage client. Endpoint override + path-style addressing are
     * what allow this AWS SDK client to speak to MinIO/R2 instead of real AWS S3.
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.isPathStyleAccess())
                        .build())
                .build();
    }

    /**
     * Generates short-lived signed URLs so clients can view/download a file without
     * the backend proxying the bytes. Same endpoint/credentials as the client.
     */
    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.isPathStyleAccess())
                        .build())
                .build();
    }
}
