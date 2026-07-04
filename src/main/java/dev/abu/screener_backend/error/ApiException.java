package dev.abu.screener_backend.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * The single application-level exception for all <em>expected</em>, client-facing failures.
 *
 * <p>It carries the HTTP status to return plus a human-readable, client-safe message (via
 * {@link #getMessage()}), so {@link GlobalExceptionHandler} can map it to a response with no
 * additional logic. Service classes throw this instead of {@code ResponseStatusException} so the
 * service layer stays free of any {@code org.springframework.web.*} dependency.
 *
 * <p>A dedicated type — rather than reusing {@link IllegalArgumentException} — means <em>we</em>
 * decide exactly which messages are surfaced to the client. Library code that throws
 * {@code IllegalArgumentException} for unrelated internal reasons never leaks through.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    // Convenience factories — keep call sites terse and intention-revealing.

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }
}
