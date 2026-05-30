# Global Exception Handling — Standardized Error Responses

## Motivation

Today, when a request fails, the caller almost always sees the same unhelpful response:
an empty body with a `403` (or, at best, a body-less `400`). The reason is twofold:

1. **Security runs before MVC.** With `anyRequest().authenticated()` and no valid JWT, the
   request is rejected at the Spring Security layer by the default `Http403ForbiddenEntryPoint`
   — `403`, empty body — *before any controller or validation runs*. (This case is **fine** and
   stays as-is: an unauthenticated caller should get an opaque 403.)
2. **Validation errors lose their message.** Even when the caller *is* authenticated, our
   services throw `ResponseStatusException(BAD_REQUEST, "...")`, and Spring Boot suppresses the
   reason in the default error body. So a genuine bad-input request returns a generic response
   with no explanation of *what* was wrong.

The result: a developer integrating against this API cannot tell an auth problem apart from a
bad-input problem. An empty 403 looks like a JWT issue even when the real cause is, say, a
`tier: 5` value in the request body. This is bad for debugging and bad for anyone building a
frontend against the API.

## Goals

1. **Standardize the error response body.** Every failed request that reaches the MVC layer
   returns the same JSON shape, with a human-readable message, the HTTP status, and the
   request path.
2. **Separate the service layer from the HTTP layer.** Service classes should throw plain
   domain exceptions carrying a message — they must not know about HTTP status codes or import
   web types. A single web-layer handler is the *only* place that maps exceptions to HTTP.
3. **Never leak internal detail.** Expected/controlled exceptions surface their message to the
   client; unexpected ones (5xx) return a generic message and are logged server-side with the
   real cause.

**Non-goal:** Changing the security/authentication behavior. The unauthenticated-request flow
(empty `403`) is intentionally left untouched. This work is purely about the MVC error path for
requests that *do* reach the dispatcher.

---

## Design

### The custom exception: `ApiException`

A single application-level exception for all *expected*, client-facing failures. It carries the
HTTP status to return plus a human-readable message, so the handler is a trivial pass-through
for it.

Conceptually:

```
ApiException extends RuntimeException
    HttpStatus status     // the HTTP status to return
    String     message    // human-readable, client-facing (from getMessage())
```

Convenience factories keep call sites short and intention-revealing, e.g.
`ApiException.badRequest("Tier must be in range [1,4]")`,
`ApiException.notFound("No rule for BTCUSDT:FUTURES")`.

> **Why a custom exception instead of reusing `IllegalArgumentException`?** Many JDK/library
> calls throw `IllegalArgumentException` for unrelated internal reasons (`UUID.fromString`,
> `Enum.valueOf`, etc.). Blanket-mapping `IllegalArgumentException -> 400 + echo its message`
> would leak an internal message to the client as if it were a validation error. A dedicated
> exception means *we* decide exactly which messages are client-facing.

### The error response body

One shape for every MVC-layer error:

```json
{
  "message": "Tier must be in range [1,4]",
  "status": 400,
  "path": "/api/rules"
}
```

- `message` — human-readable explanation; safe to display.
- `status` — numeric HTTP status (also on the response status line; duplicated here for convenience).
- `path` — the request URI, for log correlation and debugging.

### The handler: `@RestControllerAdvice`

A single global `@RestControllerAdvice` (e.g. `GlobalExceptionHandler`) holds all the
`@ExceptionHandler` methods and is the *only* place exceptions become HTTP responses.

### Exception → response mapping

| Thrown | Status | Message shown to client? |
|---|---|---|
| `ApiException` | from the exception (e.g. 400) | **yes** — it's our own, controlled message |
| `HttpMessageNotReadableException` (malformed/unparseable JSON body) | 400 | generic (`"Malformed JSON request body"`) |
| `ErrorResponseException` (Spring MVC's common 4xx base — see below) | from `ex.getStatusCode()` | generic, derived from the status |
| `RuntimeException` / `Exception` (anything unexpected) | 500 | **no** — generic `"Internal server error"`; real cause is logged |

**Why one `ErrorResponseException` handler instead of enumerating each framework exception.**
In Spring 6+, virtually every framework-raised 4xx — `NoResourceFoundException` (unknown
endpoint, 404), `HttpRequestMethodNotSupportedException` (wrong method, 405),
`HttpMediaTypeNotSupportedException` (missing/wrong `Content-Type`, 415),
`MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException`, and (if `@Valid`
is ever added) `MethodArgumentNotValidException` — extends **`ErrorResponseException`**.
Crucially, `ResponseStatusException` is *itself* a subtype of `ErrorResponseException`, so this
one handler also catches any **stray, un-migrated `ResponseStatusException`** during the service
refactor and returns its intended 4xx rather than letting it fall through to the `500` bucket.
Enumerating individual exception types would inevitably miss one (e.g. the 415 case), and a missed
4xx becomes a *masked `500`* — strictly worse than the status it should carry. Handling the base
type once is both more correct and less code.

`HttpMessageNotReadableException` is the one notable exception that is **not** an
`ErrorResponseException` (it extends `HttpMessageConversionException`), so it keeps its own
explicit handler to yield a clean `400` + "Malformed JSON" message rather than the generic 500.

Notes:
- `NoResourceFoundException -> 404` only fires for **authenticated** requests. An unknown URL
  hit without a token is still stopped by security first (empty `403`) — which is correct: the
  server should not reveal which URLs exist to anonymous callers.
- For `ErrorResponseException` the handler reads `ex.getStatusCode()` and returns a **generic**
  message keyed off the status (we do not echo Spring's internal detail to the client).
- The `500` handler logs the real exception (`log.error(..., ex)`) and returns only the generic
  `"Internal server error"` message.

---

## Refactoring: move services off `ResponseStatusException`

As part of this work, **all service classes must be scanned for use of non-standard / web-layer
exceptions** (chiefly `ResponseStatusException`, and any place a raw `IllegalArgumentException`
or similar is thrown to signal a client error) and switched to throw `ApiException` instead.
This enforces the service/HTTP separation: after the change, no service imports
`org.springframework.web.*` exception types.

(No need to enumerate every service here — the rule is: traverse the service layer, replace
client-facing exceptions with `ApiException`, and confirm no web exception types remain in
service code.) `ClassificationRuleService` is the first and most obvious one, since it is the
controller that surfaced this whole problem.

---

## Out of scope

- **Authentication/security behavior.** The unauthenticated `403` (empty body) flow is left
  exactly as it is. No changes to `SecurityConfig` or `JwtAuthenticationFilter`.

---

## Implementation

### New classes

**`error/ApiException.java`** — the application exception.
```
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    public ApiException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus getStatus() { ... }

    // Convenience factories — keep call sites terse and readable:
    public static ApiException badRequest(String message) { return new ApiException(BAD_REQUEST, message); }
    public static ApiException notFound(String message)   { return new ApiException(NOT_FOUND, message); }
    // add others (conflict, etc.) as needs arise
}
```
Responsibility: the single exception type the application layer throws for any expected,
client-facing failure. Carries the HTTP status and the client-safe message.

**`error/ApiError.java`** — the response body record.
```
public record ApiError(String message, int status, String path) {}
```
Responsibility: the uniform JSON serialized for every MVC-layer error. Built by the handler;
returned as the response body.

**`error/GlobalExceptionHandler.java`** — the `@RestControllerAdvice`.
```
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest req) {
        // status + message straight from the exception
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ... // 400, generic "Malformed JSON request body" (not an ErrorResponseException)

    @ExceptionHandler(ErrorResponseException.class)
    ResponseEntity<ApiError> handleErrorResponse(ErrorResponseException ex, HttpServletRequest req) {
        // status from ex.getStatusCode(); generic message — covers 404/405/415/missing-param and
        // any stray ResponseStatusException. Spring picks this only when no more-specific handler
        // (ApiException, HttpMessageNotReadableException) matches.
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        // 500, generic "Internal server error" — never echo ex.getMessage()
    }

    // small private helper: build(HttpStatus, message, req) -> ResponseEntity<ApiError>
}
```
Responsibility: the *only* place exceptions are translated into HTTP responses. Implements the
mapping table above. Lives under the base package so it is component-scanned automatically.

### Changes to existing classes

- **`ClassificationRuleService`** — replace every `ResponseStatusException(BAD_REQUEST, ...)` /
  `ResponseStatusException(NOT_FOUND, ...)` and the private `badRequest(...)` helper with
  `ApiException.badRequest(...)` / `ApiException.notFound(...)`. Drop the
  `org.springframework.web.server.ResponseStatusException` and `HttpStatus` imports.
- **`ClassificationRuleController`** — the `parseMarket(...)` helper currently catches
  `IllegalArgumentException` and rethrows `ResponseStatusException`; switch it to throw
  `ApiException.badRequest(...)`. (Controllers may keep throwing `ApiException`; the goal is to
  remove `ResponseStatusException`, not to forbid `ApiException` in controllers.)
- **Any other service** found using `ResponseStatusException` during the scan — same treatment.

### Implementation order

1. `ApiException` (+ factories).
2. `ApiError` record.
3. `GlobalExceptionHandler` implementing the mapping table.
4. Refactor `ClassificationRuleService` (and any other service surfaced by the scan) onto
   `ApiException`; refactor `ClassificationRuleController.parseMarket`.
5. Smoke test:
   - authenticated bad input -> descriptive `400` JSON (`{message, status, path}`);
   - malformed JSON body -> `400`;
   - unknown endpoint (authenticated) -> `404`;
   - forced internal error -> generic `500`, real cause in the logs;
   - no token -> still empty `403` (unchanged).
