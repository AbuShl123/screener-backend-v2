package dev.abu.screener_backend.binance.api;

import org.springframework.http.HttpStatusCode;

/**
 * Thrown when the Binance REST API responds with a non-2xx HTTP status code.
 *
 * <p>Callers of {@link BinanceRestClient} can catch this to inspect the original
 * HTTP status and the raw response body returned by Binance (which often contains
 * a machine-readable error code and message).
 */
public class BinanceApiException extends RuntimeException {

    private final HttpStatusCode statusCode;
    private final String responseBody;

    /**
     * @param statusCode   the HTTP status returned by Binance
     * @param responseBody the raw response body; may contain Binance error details
     */
    public BinanceApiException(HttpStatusCode statusCode, String responseBody) {
        super("Binance API error [" + statusCode + "]: " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /**
     * Returns the HTTP status code from the Binance response.
     *
     * @return HTTP status code
     */
    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the raw response body from Binance.
     *
     * @return response body string
     */
    public String getResponseBody() {
        return responseBody;
    }
}
