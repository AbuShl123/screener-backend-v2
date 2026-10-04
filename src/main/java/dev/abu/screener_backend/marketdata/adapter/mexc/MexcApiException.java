package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Venue;
import lombok.Getter;

/**
 * An API-level failure MEXC reported inside a 2xx response ({@code "success": false}).
 *
 * <p>The counterpart of {@code ExchangeApiException}, which covers non-2xx statuses. MEXC needs both:
 * throttling arrives as HTTP 200 with {@code code 510}, while an Akamai WAF block is an HTML 403.
 */
@Getter
public class MexcApiException extends RuntimeException {

    private final Venue venue;
    private final int code;

    public MexcApiException(Venue venue, int code, String message) {
        super(venue + " API error [code " + code + "]: " + message);
        this.venue = venue;
        this.code = code;
    }
}
