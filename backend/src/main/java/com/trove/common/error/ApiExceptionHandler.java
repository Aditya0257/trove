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
        return build(HttpStatus.CONFLICT, ex.getMessage(), req,
                Map.of("existingDocumentId", ex.getExistingDocumentId()));
    }

    /** Unknown resource → 404. */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req, null);
    }

    /** Authenticated but not permitted (not a member / insufficient role) → 403. */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), req, null);
    }

    /** Bad/absent credentials → 401. */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req, null);
    }

    /** Uniqueness/state conflict (e.g. email already registered) → 409. */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req, null);
    }

    /** Bean-validation failure on a request body → 400 with field messages. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        StringBuilder msg = new StringBuilder("Validation failed");
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                msg.append("; ").append(fe.getField()).append(": ").append(fe.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, msg.toString(), req, null);
    }

    /** Bad/absent arguments → 400. */
    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, null);
    }

    /** A required query parameter was missing → 400. */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, null);
    }

    /** Upload exceeded the configured multipart limit → 413. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file is too large", req, null);
    }

    /** Anything unforeseen → 500 (logged with stack trace for diagnosis). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", req, null);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message,
                                           HttpServletRequest req, Map<String, Object> details) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(),
                message, req.getRequestURI(), details);
        return ResponseEntity.status(status).body(body);
    }
}
