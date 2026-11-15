package dev.abu.screener_backend.marketdata.adapter.bybit;

import dev.abu.screener_backend.marketdata.Venue;
import lombok.Getter;

/**
 * An API-level failure Bybit reported inside a 2xx response (a non-zero {@code retCode}).
 *
 * <p>The counterpart of {@code ExchangeApiException}, which covers non-2xx statuses. Bybit needs
 * both: a rate-limited request is HTTP 200 with {@code retCode 10006} / {@code 10018}, while an IP
 * ban or a geo-block is an HTTP 403 (contracts §3.1).
 */
@Getter
public class BybitApiException extends RuntimeException {

    private final Venue venue;
    private final int retCode;

    public BybitApiException(Venue venue, int retCode, String retMsg) {
        super(venue + " API error [retCode " + retCode + "]: " + retMsg);
        this.venue = venue;
        this.retCode = retCode;
    }
}
