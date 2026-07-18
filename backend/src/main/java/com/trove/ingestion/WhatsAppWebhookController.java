/*
 * ============================================================================
 *  WhatsAppWebhookController — accept a forwarded WhatsApp document
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  The WhatsApp Cloud API verification handshake (GET) plus a webhook (POST) that
 *  files a forwarded document into a space.
 *
 *  Business use case
 *  -----------------
 *  Many people forward bills into WhatsApp. This lets a forwarded document auto-file
 *  itself through the normal review pipeline.
 *
 *  Solution architecture
 *  ---------------------
 *  Public (permitted in SecurityConfig), gated by the shared ingest secret. GET
 *  implements Meta's hub.challenge verification. POST here takes the media inline
 *  (multipart) for a working, testable flow; a production WhatsApp integration
 *  receives JSON with a media id and then fetches the bytes via the WhatsApp media
 *  API — that fetch is the seam, after which it calls the same IngestionService.
 * ============================================================================
 */
package com.trove.ingestion;

import com.trove.document.dto.DocumentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/ingest/whatsapp")
public class WhatsAppWebhookController {

    private final IngestionService ingestionService;

    public WhatsAppWebhookController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /** Meta webhook verification: echo hub.challenge when the verify token matches. */
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(value = "hub.mode", required = false) String mode,
            @RequestParam(value = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(value = "hub.challenge", required = false) String challenge) {
        try {
            ingestionService.checkAuthorized(verifyToken);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(challenge);
    }

    /** Files a forwarded WhatsApp document (media provided inline for this slice). */
    @PostMapping
    public ResponseEntity<DocumentResponse> ingest(
            @RequestParam(value = "token", required = false) String token,
            @RequestParam("spaceId") UUID spaceId,
            @RequestParam(value = "from", required = false) String from,
            @RequestPart("file") MultipartFile file) throws IOException {

        ingestionService.checkAuthorized(token);
        DocumentResponse doc = ingestionService.ingest(spaceId, file.getOriginalFilename(),
                file.getContentType(), file.getBytes(), "whatsapp:" + (from != null ? from : "unknown"));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(doc);
    }
}
