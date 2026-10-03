package dev.abu.screener_backend.exchange.binance;

import dev.abu.screener_backend.exchange.Market;

/**
 * The depth {@code limit} values every Binance venue accepts, and what one request costs at each.
 *
 * <p>Futures rejects anything else; spot accepts any value up to 1000, but one rule for every
 * Binance venue removes a class of misconfiguration. Both weight columns are confirmed
 * empirically. Resolved once, when a fetcher is built — never on the hot path.
 */
enum BinanceDepthLimit {

    L100(100, 5, 5),
    L500(500, 25, 10),
    L1000(1000, 50, 20);

    final int limit;
    private final int spotWeight;
    private final int futuresWeight;

    BinanceDepthLimit(int limit, int spotWeight, int futuresWeight) {
        this.limit = limit;
        this.spotWeight = spotWeight;
        this.futuresWeight = futuresWeight;
    }

    /** @throws IllegalArgumentException for any limit outside {100, 500, 1000} */
    static BinanceDepthLimit of(int limit) {
        for (BinanceDepthLimit value : values()) {
            if (value.limit == limit) return value;
        }
        throw new IllegalArgumentException("depth-limit must be one of 100, 500, 1000 — the only values "
                + "every Binance venue accepts — got: " + limit);
    }

    /** Request weight of one depth call at this limit. */
    int weight(Market market) {
        return switch (market) {
            case SPOT -> spotWeight;
            case FUTURES -> futuresWeight;
        };
    }
}
