/*
 * ============================================================================
 *  EmailIngestController — accept a forwarded email's attachment
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  A public webhook an inbound-email provider (Mailgun/SendGrid/etc.) posts to when
 *  a user forwards a document; it files the attachment into a space.
 *
 *  Business use case
 *  -----------------
 *  "Forward the bill and it files itself." Meets the habit of forwarding documents,
 *  routing them through the normal review pipeline.
 *
 *  Solution architecture
 *  ---------------------
 *  Public (permitted in SecurityConfig) but gated by the shared ingest secret. The
 *  attachment is passed as multipart 'file'; routing is by spaceId. Real providers
 *  post richer payloads (from/subject/multiple attachments) — those map onto the same
 *  IngestionService call.
 * ============================================================================
 */
package com.trove.ingestion;

import com.trove.document.dto.DocumentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/ingest/email")
public class EmailIngestController {

    private final IngestionService ingestionService;

    public EmailIngestController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Files a forwarded attachment. `token` may be a per-space ingest token (routes
     * to that space) or the shared secret (then spaceId is required).
     */
    @PostMapping
    public ResponseEntity<DocumentResponse> ingest(
            @RequestParam(value = "token", required = false) String token,
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "from", required = false) String from,
            @RequestPart("file") MultipartFile file) throws IOException {

        UUID space = ingestionService.resolveSpace(token, spaceId);
        DocumentResponse doc = ingestionService.ingest(space, file.getOriginalFilename(),
                file.getContentType(), file.getBytes(), "email:" + (from != null ? from : "unknown"));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(doc);
    }
}
