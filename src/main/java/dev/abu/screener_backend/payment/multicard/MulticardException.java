package dev.abu.screener_backend.payment.multicard;

/**
 * Raised when a Multicard API call fails — a non-2xx HTTP status, or a {@code success=false} envelope.
 * Carries the provider error code/details when available for the audit trail.
 */
public class MulticardException extends RuntimeException {

    private final String code;

    public MulticardException(String message, String code) {
        super(message);
        this.code = code;
    }

    public MulticardException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
    }

    public String getCode() {
        return code;
    }
}
