package dev.abu.screener_backend.exchange.stream;

import dev.abu.screener_backend.config.ExchangesProperties;
import dev.abu.screener_backend.config.WebSocketProperties;
import dev.abu.screener_backend.exchange.Instrument;
import dev.abu.screener_backend.exchange.InstrumentUniverseChangedEvent;
import dev.abu.screener_backend.exchange.Venue;
import dev.abu.screener_backend.exchange.health.PipelineMetrics;
import dev.abu.screener_backend.exchange.ingress.DepthEventPublisher;
import dev.abu.screener_backend.exchange.spi.StreamProtocol;
import dev.abu.screener_backend.exchange.spi.StreamProtocolRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Starts one {@link ConnectionPool} per enabled venue, the first time that venue shows up in a
 * universe event.
 *
 * <h3>The invariant this relies on</h3>
 * The first time a venue appears in {@code added}, that list is the venue's <b>entire</b> current
 * universe. {@code InstrumentUniverseService.apply()} only adds instruments that
 * {@code registry.find} has never seen, and a venue's first successful fetch has no earlier
 * registrations. That is what makes per-venue start correct without dynamic subscribe. It holds for
 * every first-appearance case: a venue whose first fetch returns an empty list gets no pool, and its
 * first non-empty fetch is again all-new.
 *
 * <p><b>Known limitation</b>: if starting a venue's pool throws, the venue gets no pool and a later
 * event may retry it — but with only that event's <em>new</em> instruments, so the retried pool
 * would be partial. {@link ConnectionPool#start} cannot realistically throw today ({@code connect()}
 * is async and a bad URL is caught inside), so the failure is only logged. Dynamic subscribe removes
 * the limitation.
 */
@Slf4j
@Component
public class StreamManager {

    /** Test seam: builds a pool for a venue. Production wires the real constructor. */
    @FunctionalInterface
    interface PoolFactory {
        ConnectionPool create(Venue venue, StreamProtocol protocol);
    }

    private final ExchangesProperties exchanges;
    private final StreamProtocolRegistry protocols;
    private final PoolFactory poolFactory;

    private final Map<Venue, ConnectionPool> pools = new EnumMap<>(Venue.class); // guarded by this
    private boolean shutDown;                                                     // guarded by this

    @Autowired
    public StreamManager(ExchangesProperties exchanges, WebSocketProperties wsProps,
                         StreamProtocolRegistry protocols, DepthEventPublisher publisher,
                         PipelineMetrics metrics) {
        this(exchanges, protocols, (venue, protocol) ->
                new ConnectionPool(venue, exchanges.venue(venue), wsProps, protocol, publisher, metrics));
    }

    StreamManager(ExchangesProperties exchanges, StreamProtocolRegistry protocols, PoolFactory poolFactory) {
        this.exchanges = exchanges;
        this.protocols = protocols;
        this.poolFactory = poolFactory;
    }

    /**
     * Runs synchronously on the discovery thread, after slots have been published — so every id
     * these subscriptions can produce already has a book slot behind it. Never throws back into
     * discovery: a venue that fails to start is logged and isolated from the others.
     */
    @EventListener
    public synchronized void onUniverseChanged(InstrumentUniverseChangedEvent event) {
        if (shutDown) return;

        Map<Venue, List<Instrument>> added = byVenue(event.getAdded());
        Map<Venue, List<Instrument>> removed = byVenue(event.getRemoved());

        // Venues that already had a pool before this event — their changes are not applied yet.
        List<Venue> existing = new ArrayList<>(pools.keySet());

        for (Map.Entry<Venue, List<Instrument>> entry : added.entrySet()) {
            Venue venue = entry.getKey();
            if (pools.containsKey(venue)) continue;
            if (!exchanges.isEnabled(venue)) {
                log.warn("[{}] Universe event adds {} instruments for a disabled venue — not streaming",
                        venue, entry.getValue().size());
                continue;
            }
            startPool(venue, entry.getValue());
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

    private void startPool(Venue venue, List<Instrument> instruments) {
        try {
            ConnectionPool pool = poolFactory.create(venue, protocols.forVenue(venue));
            pool.start(instruments);
            pools.put(venue, pool);
        } catch (RuntimeException e) {
            log.error("[{}] Failed to start WebSocket pool — the venue will not stream until a later "
                    + "universe event retries it (with that event's new instruments only)", venue, e);
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
