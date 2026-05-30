package dev.abu.screener_backend.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single, global translation point from exceptions to HTTP error responses. Every failed
 * request that reaches the MVC dispatcher returns the same {@link ApiError} shape.
 *
 * <p>Mapping:
 * <ul>
 *   <li>{@link ApiException} — our own controlled failure; status and message pass straight
 *       through to the client.</li>
 *   <li>{@link HttpMessageNotReadableException} — malformed/unparseable JSON body; {@code 400}
 *       with a generic message. (Not an {@link ErrorResponseException}, so it needs its own
 *       handler.)</li>
 *   <li>{@link ErrorResponseException} — Spring 6's common 4xx base (unknown endpoint 404, wrong
 *       method 405, missing {@code Content-Type} 415, missing/mistyped params, and any stray
 *       {@code ResponseStatusException}). Status comes from {@code getStatusCode()}; message is
 *       generic, derived from the status — Spring's internal detail is never echoed.</li>
 *   <li>{@link Exception} — anything unexpected; {@code 500} with a generic message. The real
 *       cause is logged server-side and never leaked to the client.</li>
 * </ul>
 *
 * <p>Authentication is out of scope: unauthenticated requests are rejected by Spring Security
 * before the dispatcher runs (empty {@code 403}), so they never reach this advice.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest req) {
        return build(ex.getStatus(), ex.getMessage(), req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Malformed JSON request body", req);
    }

    @ExceptionHandler(ErrorResponseException.class)
    ResponseEntity<ApiError> handleErrorResponse(ErrorResponseException ex, HttpServletRequest req) {
        HttpStatusCode status = ex.getStatusCode();
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String reason = resolved != null ? resolved.getReasonPhrase() : "Request failed";
        return build(status, reason, req);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", req);
    }

    private static ResponseEntity<ApiError> build(HttpStatusCode status, String message, HttpServletRequest req) {
        ApiError body = new ApiError(message, status.value(), req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
