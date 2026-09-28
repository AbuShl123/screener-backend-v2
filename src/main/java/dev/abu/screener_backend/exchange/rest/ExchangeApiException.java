package dev.abu.screener_backend.exchange.rest;

import dev.abu.screener_backend.exchange.Venue;
import lombok.Getter;
import org.springframework.http.HttpStatusCode;

/**
 * Thrown when a venue's REST API responds with a non-2xx HTTP status code.
 *
 * <p>Callers can inspect the venue, the original HTTP status, and the raw response body — which
 * often carries a machine-readable error code an adapter can act on. Deliberately thin for now: no
 * classification of the error (rate-limited vs. banned vs. bad request) — that mapping is adapter
 * work for later.
 */
@Getter
public class ExchangeApiException extends RuntimeException {

    private final Venue venue;
    private final HttpStatusCode statusCode;
    private final String responseBody;

    /**
     * @param venue        the venue whose REST call failed
     * @param statusCode   the HTTP status returned by the exchange
     * @param responseBody the raw response body; may contain exchange-specific error details
     */
    public ExchangeApiException(Venue venue, HttpStatusCode statusCode, String responseBody) {
        super(venue + " REST error [" + statusCode + "]: " + responseBody);
        this.venue = venue;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

}
