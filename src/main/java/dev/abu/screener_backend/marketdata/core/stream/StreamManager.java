package dev.abu.screener_backend.marketdata.core.stream;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.marketdata.Instrument;
import dev.abu.screener_backend.marketdata.InstrumentUniverseChangedEvent;
import dev.abu.screener_backend.marketdata.StreamKey;
import dev.abu.screener_backend.marketdata.StreamKind;
import dev.abu.screener_backend.marketdata.Venue;
import dev.abu.screener_backend.marketdata.core.health.PipelineMetrics;
import dev.abu.screener_backend.marketdata.core.ingress.MarketEventPublisher;
import dev.abu.screener_backend.marketdata.spi.StreamProtocol;
import dev.abu.screener_backend.marketdata.spi.StreamProtocolRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Starts one {@link ConnectionPool} per enabled stream ({@link StreamKey}) of a venue, the first
 * time that venue shows up in a universe event.
 *
 * <h3>The invariant this relies on</h3>
 * The first time a venue appears in {@code added}, that list is the venue's <b>entire</b> current
 * universe. {@code InstrumentUniverseService.apply()} only adds instruments that
 * {@code registry.find} has never seen, and a venue's first successful fetch has no earlier
 * registrations. That is what makes start-on-first-appearance correct without dynamic subscribe. It
 * holds for every first-appearance case: a venue whose first fetch returns an empty list gets no
 * pools, and its first non-empty fetch is again all-new.
 *
 * <p>The invariant is per venue, so "has it started?" is tracked per venue, not per stream. On that
 * first appearance every enabled stream of the venue gets its own pool, and each pool gets the
 * venue's full instrument list.
 *
 * <p><b>Known limitation</b>: if starting one stream's pool throws, that stream gets no pool and
 * stays down until restart; the venue's other streams still start. It is not retried on a later
 * event, because a later event carries only <em>new</em> instruments and the retried pool would be
 * partial. {@link ConnectionPool#start} cannot realistically throw today ({@code connect()} is
 * async and a bad URL is caught inside), so the failure is only logged. Dynamic subscribe removes
 * the limitation.
 */
@Slf4j
@Component
public class StreamManager {

    /** Test seam: builds a pool for a stream. Production wires the real constructor. */
    @FunctionalInterface
    interface PoolFactory {
        ConnectionPool create(StreamKey stream, StreamProtocol protocol);
    }

    private final ExchangesProperties exchanges;
    private final StreamProtocolRegistry protocols;
    private final PoolFactory poolFactory;

    private final Map<StreamKey, ConnectionPool> pools = new LinkedHashMap<>(); // guarded by this
    private final Set<Venue> startedVenues = EnumSet.noneOf(Venue.class);        // guarded by this
    private boolean shutDown;                                                     // guarded by this

    @Autowired
    public StreamManager(ExchangesProperties exchanges, WebSocketProperties wsProps,
                         StreamProtocolRegistry protocols, MarketEventPublisher publisher,
                         PipelineMetrics metrics) {
        this(exchanges, protocols, (stream, protocol) -> new ConnectionPool(stream,
                exchanges.stream(stream), wsProps, protocol, publisher, metrics));
    }

    StreamManager(ExchangesProperties exchanges, StreamProtocolRegistry protocols, PoolFactory poolFactory) {
        this.exchanges = exchanges;
        this.protocols = protocols;
        this.poolFactory = poolFactory;
    }

    /**
     * Runs synchronously on the discovery thread, after slots have been published — so every id
     * these subscriptions can produce already has an instrument slot behind it. Never throws back into
     * discovery: a stream that fails to start is logged and isolated from the others.
     */
    @EventListener
    public synchronized void onUniverseChanged(InstrumentUniverseChangedEvent event) {
        if (shutDown) return;

        Map<Venue, List<Instrument>> added = byVenue(event.getAdded());
        Map<Venue, List<Instrument>> removed = byVenue(event.getRemoved());

        // Venues that had started before this event — their changes are not applied yet.
        List<Venue> existing = new ArrayList<>(startedVenues);

        for (Map.Entry<Venue, List<Instrument>> entry : added.entrySet()) {
            Venue venue = entry.getKey();
            if (startedVenues.contains(venue)) continue;
            if (!exchanges.isEnabled(venue)) {
                log.warn("[{}] Universe event adds {} instruments for a disabled venue — not streaming",
                        venue, entry.getValue().size());
                continue;
            }
            startedVenues.add(venue);
            for (StreamKind kind : StreamKind.values()) {
                StreamKey stream = new StreamKey(venue, kind);
                if (exchanges.isEnabled(stream)) {
                    startPool(stream, entry.getValue());
                }
            }
        }

        for (Venue venue : existing) {
            int plus = added.getOrDefault(venue, List.of()).size();
            int minus = removed.getOrDefault(venue, List.of()).size();
            if (plus > 0 || minus > 0) {
                log.info("[{}] Universe changed (+{} / -{}) — dynamic re-subscription not yet implemented",
                        venue, plus, minus);
            }
        }
    }

    private void startPool(StreamKey stream, List<Instrument> instruments) {
        try {
            ConnectionPool pool = poolFactory.create(stream, protocols.forStream(stream));
            pool.start(instruments);
            pools.put(stream, pool);
        } catch (RuntimeException e) {
            log.error("[{}] Failed to start WebSocket pool — the stream stays down until restart; "
                    + "the venue's other streams are unaffected", stream, e);
        }
    }

    /** Preserves order within a venue; {@code added} arrives in id order. */
    private static Map<Venue, List<Instrument>> byVenue(List<Instrument> instruments) {
        Map<Venue, List<Instrument>> out = new EnumMap<>(Venue.class);
        for (Instrument instrument : instruments) {
            out.computeIfAbsent(instrument.venue(), v -> new ArrayList<>()).add(instrument);
        }
        return out;
    }

    @PreDestroy
    public synchronized void shutdown() {
        shutDown = true;
        pools.values().forEach(ConnectionPool::shutdown);
        pools.clear();
    }
}
