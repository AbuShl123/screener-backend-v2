package dev.abu.screener_backend.auth;

/**
 * Result of a verify-email attempt. The controller maps it to the SPA landing redirect
 * ({@code ?status=success|expired|invalid}) — never a JSON body.
 */
public enum EmailVerificationOutcome {
    SUCCESS("success"),
    EXPIRED("expired"),
    INVALID("invalid");

    private final String status;

    EmailVerificationOutcome(String status) {
        this.status = status;
    }

    /** The value appended as {@code ?status=...} on the frontend redirect URL. */
    public String status() {
        return status;
    }
}
