package dev.abu.screener_backend.exchange.spi;

import dev.abu.screener_backend.exchange.Venue;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports the tradable universe for one or more venues of a single exchange.
 *
 * <p>Contributed as a {@code @Bean} from an adapter's configuration, never component-scanned.
 * A source spans several venues when their inclusion policies depend on each other (Binance:
 * spot requires futures); independent venues should be independent sources, so a failure in one
 * does not freeze the other.
 *
 * <p>Core ({@code InstrumentUniverseService}) enforces that at most one source claims a venue, and
 * owns everything id-shaped: ordering, registration and the added/removed diff. A source therefore
 * never sorts its output.
 */
public interface InstrumentSource {

    /** The venues this source is authoritative for. Non-empty, all of one exchange, stable. */
    Set<Venue> venues();

    /**
     * Blocking; called off the hot path on a discovery worker thread, which is interrupted if the
     * call outlives {@code screener.discovery.source-timeout}.
     *
     * <p>Returns the inclusion-filtered universe for <b>every</b> venue in {@link #venues()}.
     * All-or-nothing: on any failure, throw. Never return a partial map, and never return an
     * empty list to signal failure. An empty list means "this venue genuinely lists nothing", and
     * core will treat it as a mass delisting unless its empty-result guard catches it.
     */
    Map<Venue, List<InstrumentCandidate>> fetch();
}
