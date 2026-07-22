/*
 * ============================================================================
 *  ApiExceptionHandler — translates exceptions into uniform HTTP responses
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  One place that maps domain/validation exceptions to HTTP status codes and the
 *  ApiError body, so controllers stay focused on the happy path.
 *
 *  Business use case
 *  -----------------
 *  Predictable errors make the API pleasant to integrate against and make failures
 *  (duplicate upload, missing document, bad request) legible to the user.
 *
 *  Solution architecture
 *  ---------------------
 *  @RestControllerAdvice intercepts exceptions thrown anywhere in the request path.
 *  Duplicate → 409 (with the existing id), NotFound → 404, validation → 400,
 *  anything unexpected → 500 (logged).
 *
 *  Reasoning & logic
 *  -----------------
 *  The 409 case surfaces the existing document id in `details` so a client can link
 *  the user straight to the already-stored document instead of re-uploading.
 * ============================================================================
 */
package com.trove.common.error;

import com.trove.common.notice.ApiNotice;
import com.trove.common.notice.NoticeLevel;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Same content already stored in this space → 409 with the existing id. */
    @ExceptionHandler(DuplicateDocumentException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateDocumentException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, req, ApiNotice.of(NoticeLevel.WARNING, "DUPLICATE_DOCUMENT",
                "You've already saved this document. Opening the existing one.",
                "Content hash matched an existing document in this space; upload skipped.",
                Map.of("existingDocumentId", ex.getExistingDocumentId())));
    }

    /** Unknown resource → 404. */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, req, ApiNotice.of(NoticeLevel.WARNING, "NOT_FOUND",
                "We couldn't find that.",
                safe(ex.getMessage(), "Resource not found."), null));
    }

    /** Authenticated but not permitted (not a member / insufficient role) → 403. */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, req, ApiNotice.of(NoticeLevel.WARNING, "FORBIDDEN",
                "You don't have access to this.",
                safe(ex.getMessage(), "Not a member, or insufficient role for this space."), null));
    }

    /** Bad/absent credentials → 401. */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, req, ApiNotice.of(NoticeLevel.WARNING, "UNAUTHENTICATED",
                "Please sign in again.",
                "Missing or invalid credentials (JWT absent, malformed, or expired).", null));
    }

    /** Uniqueness/state conflict (e.g. email already registered) → 409. */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, req, ApiNotice.of(NoticeLevel.WARNING, "CONFLICT",
                "That conflicts with something that already exists.",
                safe(ex.getMessage(), "Uniqueness/state conflict."), null));
    }

    /** Bean-validation failure on a request body → 400 with field messages. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        StringBuilder dev = new StringBuilder("Validation failed");
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                dev.append("; ").append(fe.getField()).append(": ").append(fe.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, req, ApiNotice.of(NoticeLevel.WARNING, "VALIDATION",
                "Some fields need attention.", dev.toString(), null));
    }

    /** Bad/absent arguments → 400. */
    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, req, ApiNotice.of(NoticeLevel.WARNING, "BAD_REQUEST",
                "That request wasn't quite right.",
                safe(ex.getMessage(), "Illegal argument."), null));
    }

    /** A required query parameter was missing → 400. */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, req, ApiNotice.of(NoticeLevel.WARNING, "MISSING_PARAM",
                "A required detail was missing.", safe(ex.getMessage(), "Missing request parameter."), null));
    }

    /** Upload exceeded the configured multipart limit → 413. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, req, ApiNotice.of(NoticeLevel.WARNING, "FILE_TOO_LARGE",
                "That file is too large to upload.", "Exceeds the configured multipart upload limit.", null));
    }

    /** Anything unforeseen → 500 (logged with stack trace for diagnosis). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        // NOTE: the raw exception message is deliberately NOT put in devNote — it can
        // contain sensitive fragments (SQL, connection details). It stays in the server
        // log; clients correlate via the X-Trove-Request-Id response header. We do name
        // the exception TYPE, which is safe and useful.
        return build(HttpStatus.INTERNAL_SERVER_ERROR, req, ApiNotice.of(NoticeLevel.ERROR, "INTERNAL_ERROR",
                "Something went wrong on our side. We've logged it.",
                "Unhandled " + ex.getClass().getSimpleName()
                        + "; see server logs (correlate by the X-Trove-Request-Id response header).", null));
    }

    /** Falls back to a generic developer note when an exception carries no message. */
    private String safe(String message, String fallback) {
        return (message == null || message.isBlank()) ? fallback : message;
    }

    private ResponseEntity<ApiError> build(HttpStatus status, HttpServletRequest req, ApiNotice notice) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(),
                notice.userMessage(), req.getRequestURI(), notice.meta(), notice);
        return ResponseEntity.status(status).body(body);
    }
}
