package dev.abu.screener_backend.exchange.rest;

import dev.abu.screener_backend.exchange.Venue;
import lombok.Getter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

/**
 * Thrown when a venue's REST API responds with a non-2xx HTTP status code.
 *
 * <p>Callers can inspect the venue, the original HTTP status, the response headers and the raw
 * body. The headers matter to rate limiting: a 429 carries {@code Retry-After} and the venue's
 * usage counters just like a success does. Deliberately thin: no classification of the error
 * (rate-limited vs. banned vs. bad request) — that mapping is adapter work.
 */
@Getter
public class ExchangeApiException extends RuntimeException {

    private final Venue venue;
    private final HttpStatusCode statusCode;
    private final HttpHeaders headers;
    private final String responseBody;

    /**
     * @param venue        the venue whose REST call failed
     * @param statusCode   the HTTP status returned by the exchange
     * @param headers      the response headers; empty when unknown
     * @param responseBody the raw response body; may contain exchange-specific error details
     */
    public ExchangeApiException(Venue venue, HttpStatusCode statusCode, HttpHeaders headers, String responseBody) {
        super(venue + " REST error [" + statusCode + "]: " + responseBody);
        this.venue = venue;
        this.statusCode = statusCode;
        this.headers = headers == null ? HttpHeaders.EMPTY : headers;
        this.responseBody = responseBody;
    }

    public ExchangeApiException(Venue venue, HttpStatusCode statusCode, String responseBody) {
        this(venue, statusCode, HttpHeaders.EMPTY, responseBody);
    }

    /**
     * Reads an error response into an exception, for use as a WebClient {@code onStatus} handler.
     *
     * <p>{@code defaultIfEmpty} matters: an error status with an empty body would otherwise map to an
     * empty {@code Mono}, which {@code onStatus} treats as "not an error" — and a bodiless 429
     * would then be handed to the caller as a success.
     */
    public static Mono<ExchangeApiException> from(Venue venue, ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new ExchangeApiException(venue, response.statusCode(),
                        response.headers().asHttpHeaders(), body));
    }
}
