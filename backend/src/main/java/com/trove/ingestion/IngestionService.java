/*
 * ============================================================================
 *  IngestionService — routes forwarded documents into the upload pipeline
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Validates the shared ingest secret, wraps forwarded bytes as a MultipartFile, and
 *  runs them through the same DocumentService pipeline as a normal upload.
 *
 *  Business use case
 *  -----------------
 *  People already forward bills into chat/email. Meeting that habit — forward a
 *  document and it auto-files itself — is the high-value ingestion feature.
 *
 *  Solution architecture
 *  ---------------------
 *  Reuses DocumentService.upload (dedupe, store + sidecar, async extraction,
 *  needs_review) via ByteArrayMultipartFile. The document is attributed to the target
 *  space's owner. Called by the email/WhatsApp controllers.
 *
 *  Reasoning & logic
 *  -----------------
 *  Central token check keeps both webhooks consistent. Duplicate forwards are handled
 *  for free by the pipeline's content-hash dedupe. Everything still lands in
 *  needs_review — a human confirms, exactly as with uploads.
 * ============================================================================
 */
package com.trove.ingestion;

import com.trove.common.ByteArrayMultipartFile;
import com.trove.common.error.UnauthorizedException;
import com.trove.document.DocumentService;
import com.trove.document.dto.DocumentResponse;
import com.trove.space.SpaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final DocumentService documentService;
    private final SpaceService spaceService;
    private final IngestProperties props;

    public IngestionService(DocumentService documentService, SpaceService spaceService,
                            IngestProperties props) {
        this.documentService = documentService;
        this.spaceService = spaceService;
        this.props = props;
    }

    /** Validates the shared secret; throws 401 on mismatch or when ingestion is off. */
    public void checkAuthorized(String token) {
        if (!props.isEnabled()) {
            throw new UnauthorizedException("Ingestion is disabled");
        }
        if (props.getSecret() == null || props.getSecret().isBlank()
                || !props.getSecret().equals(token)) {
            throw new UnauthorizedException("Invalid ingest token");
        }
    }

    /**
     * Ingests forwarded bytes into a space (attributed to the space owner) via the
     * standard document pipeline. `source` is for logging/provenance.
     */
    public DocumentResponse ingest(UUID spaceId, String filename, String contentType,
                                   byte[] bytes, String source) {
        UUID owner = spaceService.ownerId(spaceId);
        MultipartFile file = new ByteArrayMultipartFile("file",
                filename != null ? filename : "forwarded",
                contentType != null ? contentType : "application/octet-stream", bytes);
        DocumentResponse doc = documentService.upload(spaceId, owner, file);
        log.info("Ingested document {} into space {} via {} ({} bytes)",
                doc.id(), spaceId, source, bytes != null ? bytes.length : 0);
        return doc;
    }
}
