package dev.abu.screener_backend.marketdata.adapter.mexc;

import dev.abu.screener_backend.marketdata.Venue;
import reactor.core.publisher.Mono;

/**
 * What {@link MexcSnapshotFetcher} needs from one MEXC venue's REST API: send a depth request, and
 * say what a 2xx body means. Spot and futures are different APIs — futures wraps every body in a
 * {@code {success, code, data}} envelope and throttles with a 200, spot returns the book bare and
 * fails with a non-2xx — so each client reads its own bodies, and the fetcher's pacing and
 * cooldowns stay shared.
 */
interface MexcDepthClient {

    /** What a 2xx depth body says. */
    enum BodyKind {
        /** A snapshot: hand it to the sync strategy. */
        OK,
        /** MEXC asks us to slow down (futures {@code code 510}); start the throttle cooldown. */
        THROTTLED,
        /** An API-level error for this request only. */
        REJECTED,
        /** Not a body this venue sends: empty, HTML, or an unknown shape. */
        MALFORMED
    }

    Venue venue();

    /**
     * One symbol's order book, as the raw body. Cold. A non-2xx status travels the error channel as
     * an {@code ExchangeApiException}, logged at WARN first.
     */
    Mono<String> depth(String symbol, int limit);

    /** Classifies a 2xx body without parsing its levels. */
    BodyKind classifyDepth(String body);
}
