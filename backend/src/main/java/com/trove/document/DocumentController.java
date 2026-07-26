/*
 * ============================================================================
 *  DocumentController — REST surface for documents (authenticated)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  HTTP endpoints for the document flow: upload, list by category, fetch one, get a
 *  fresh view URL, and confirm a review — all on behalf of the authenticated user.
 *
 *  Business use case
 *  -----------------
 *  What the web/mobile clients (and curl) call. The acting user comes from the JWT;
 *  the target space defaults to the user's personal space when not specified.
 *
 *  Solution architecture
 *  ---------------------
 *  Base path /api/documents (all authenticated). Identity from CurrentUser;
 *  space membership/role enforcement lives in DocumentService/SpaceAuthorization.
 *  A missing spaceId resolves to the caller's personal space via SpaceService.
 *
 *  Reasoning & logic
 *  -----------------
 *  Controller stays thin: resolve user + space, delegate. Upload is multipart;
 *  everything else JSON. Upload returns 201 with status=needs_review.
 * ============================================================================
 */
package com.trove.document;

import com.trove.common.security.CurrentUser;
import com.trove.document.dto.ConfirmRequest;
import com.trove.document.dto.DocumentResponse;
import com.trove.space.SpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final SpaceService spaceService;
    private final CurrentUser currentUser;

    public DocumentController(DocumentService documentService, SpaceService spaceService,
                             CurrentUser currentUser) {
        this.documentService = documentService;
        this.spaceService = spaceService;
        this.currentUser = currentUser;
    }

    /** Upload a document (multipart). Defaults to the caller's personal space.
     *  Pass vital=true for sensitive PII (passport/ID/policy) → encrypted at rest. */
    @PostMapping
    public ResponseEntity<DocumentResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "vital", required = false, defaultValue = "false") boolean vital,
            @RequestParam(value = "extract", required = false, defaultValue = "true") boolean extract,
            @RequestParam(value = "reuseExisting", required = false, defaultValue = "false") boolean reuseExisting) {

        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        DocumentResponse created = documentService.upload(space, user, file, vital, extract, reuseExisting);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Stream the original file bytes (decrypted if the document is vital/encrypted). */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        DocumentService.DownloadedFile f = documentService.content(id, currentUser.requireUserId());
        String filename = f.filename() != null ? f.filename() : "document";
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        f.contentType() != null ? f.contentType() : "application/octet-stream"))
                .body(f.bytes());
    }

    /**
     * List documents in a space (defaults to personal), optionally by category and paged.
     * The body is the page of documents; the total match count rides in the X-Total-Count
     * header so the client can build a pager without a second call. Omitting size (or 0)
     * returns every match - the back-compatible behaviour for callers that don't page and
     * for the browser-find / export-all view.
     */
    @GetMapping
    public ResponseEntity<List<DocumentResponse>> list(
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "0") int size) {

        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        DocumentService.Paged<DocumentResponse> paged = documentService.listPaged(space, user, category, page, size);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(paged.total()))
                .body(paged.items());
    }

    /** The email documents in one mail bundle (thread), oldest first, so the Mail detail view
     *  loads just that thread instead of every email in the space. */
    @GetMapping("/mail-bundle")
    public List<DocumentResponse> mailBundle(
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam("bundleId") String bundleId) {
        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        return documentService.listMailBundle(space, user, bundleId);
    }

    /** Fetch one document by id (must be a member of its space). */
    @GetMapping("/{id}")
    public DocumentResponse get(@PathVariable UUID id) {
        return documentService.get(id, currentUser.requireUserId());
    }

    /** Return a fresh short-lived URL to view/download the original file. */
    @GetMapping("/{id}/file")
    public Map<String, String> fileUrl(@PathVariable UUID id) {
        return Map.of("url", documentService.get(id, currentUser.requireUserId()).fileUrl());
    }

    /** Confirm a document's review (optionally with edits). */
    @PostMapping("/{id}/confirm")
    public DocumentResponse confirm(
            @PathVariable UUID id,
            @RequestBody(required = false) ConfirmRequest body) {

        return documentService.confirm(id, currentUser.requireUserId(), body);
    }

    /** Soft-deletes a document: moves it to the trash (recoverable), status=deleted. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        documentService.delete(id, currentUser.requireUserId());
    }

    /** The trash: soft-deleted documents in a space, most recently deleted first. */
    @GetMapping("/trash")
    public List<DocumentResponse> trash(@RequestParam(value = "spaceId", required = false) UUID spaceId) {
        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        return documentService.listTrash(space, user);
    }

    /** Restores a trashed document back to the live vault. */
    @PostMapping("/{id}/restore")
    public void restore(@PathVariable UUID id) {
        documentService.restore(id, currentUser.requireUserId());
    }

    /** Permanently purges one trashed document now ("delete forever"). Write access required. */
    @DeleteMapping("/{id}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable UUID id) {
        documentService.purgeNow(id, currentUser.requireUserId());
    }
}
