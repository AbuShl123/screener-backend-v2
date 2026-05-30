package dev.abu.screener_backend.error;

/**
 * The uniform JSON body returned for every MVC-layer error. Built by
 * {@link GlobalExceptionHandler} and serialized as the response body.
 *
 * <pre>
 * {
 *   "message": "Tier must be in range [1,4]",
 *   "status": 400,
 *   "path": "/api/rules"
 * }
 * </pre>
 *
 * @param message human-readable explanation; safe to display to the client
 * @param status  numeric HTTP status (also on the response status line; duplicated for convenience)
 * @param path    the request URI, for log correlation and debugging
 */
public record ApiError(String message, int status, String path) {
}
