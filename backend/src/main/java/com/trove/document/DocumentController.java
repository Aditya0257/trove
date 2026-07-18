/*
 * ============================================================================
 *  DocumentController — REST surface for Slice 1
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  HTTP endpoints for the vertical slice: upload a document, list by category,
 *  fetch one, get a fresh view URL, and confirm a review.
 *
 *  Business use case
 *  -----------------
 *  This is what the (future) web/mobile clients — and curl, today — call to drive
 *  the whole flow. It is intentionally thin: all logic lives in DocumentService.
 *
 *  Solution architecture
 *  ---------------------
 *  Base path /api/documents. Until real auth exists, space and user default to the
 *  seeded dev identity (DevProperties / Flyway V6, DECISIONS.md → D6); callers may
 *  override spaceId/uploadedBy via params for testing multiple spaces.
 *
 *  Reasoning & logic
 *  -----------------
 *  Upload is multipart (a file). Everything else is JSON. Upload returns 201; the
 *  response shows status=needs_review with extraction pending (it fills in shortly).
 * ============================================================================
 */
package com.trove.document;

import com.trove.common.DevProperties;
import com.trove.document.dto.ConfirmRequest;
import com.trove.document.dto.DocumentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final DevProperties dev;

    public DocumentController(DocumentService documentService, DevProperties dev) {
        this.documentService = documentService;
        this.dev = dev;
    }

    /** Upload a document (multipart). Defaults to the seeded dev space + user. */
    @PostMapping
    public ResponseEntity<DocumentResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "uploadedBy", required = false) UUID uploadedBy) {

        UUID space = spaceId != null ? spaceId : dev.getDefaultSpaceId();
        UUID user = uploadedBy != null ? uploadedBy : dev.getDefaultUserId();
        DocumentResponse created = documentService.upload(space, user, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** List documents in a space, optionally filtered by category code. */
    @GetMapping
    public List<DocumentResponse> list(
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "category", required = false) String category) {

        UUID space = spaceId != null ? spaceId : dev.getDefaultSpaceId();
        return documentService.list(space, category);
    }

    /** Fetch one document by id. */
    @GetMapping("/{id}")
    public DocumentResponse get(@PathVariable UUID id) {
        return documentService.get(id);
    }

    /** Return a fresh short-lived URL to view/download the original file. */
    @GetMapping("/{id}/file")
    public Map<String, String> fileUrl(@PathVariable UUID id) {
        return Map.of("url", documentService.get(id).fileUrl());
    }

    /** Confirm a document's review (optionally with edits). Defaults reviewer to dev user. */
    @PostMapping("/{id}/confirm")
    public DocumentResponse confirm(
            @PathVariable UUID id,
            @RequestBody(required = false) ConfirmRequest body,
            @RequestParam(value = "reviewerId", required = false) UUID reviewerId) {

        UUID reviewer = reviewerId != null ? reviewerId : dev.getDefaultUserId();
        return documentService.confirm(id, reviewer, body);
    }
}
