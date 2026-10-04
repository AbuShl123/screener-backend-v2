package dev.abu.screener_backend.marketdata;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * Published by {@link InstrumentUniverseService} after a successful universe refresh.
 *
 * <p><b>Ordering is an invariant.</b> The discovery thread registers every instrument, allocates
 * and {@code publish()}es their book slots, and only then fires this event. Listeners (the
 * WebSocket transport) may therefore assume that a slot exists for every id they will see. If the
 * event were fired first, a subscribe frame could produce a depth message whose id is past the end
 * of the published slot array.
 *
 * <p>Replaces {@code TickersRefreshedEvent}. Note that {@code added} carries {@link Instrument}s,
 * not symbols.
 *
 * <p><b>Per-venue invariant the transport relies on:</b> the first time a venue appears in
 * {@code added}, that list is the venue's <em>entire</em> current universe. {@code apply()} only adds
 * instruments that {@code registry.find} has never seen, and a venue's first successful fetch has no
 * earlier registrations. The transport starts a venue's connection pool from exactly that list.
 */
@Getter
public class InstrumentUniverseChangedEvent extends ApplicationEvent {

    private final List<Instrument> added;
    private final List<Instrument> removed;

    public InstrumentUniverseChangedEvent(Object source, List<Instrument> added, List<Instrument> removed) {
        super(source);
        this.added = List.copyOf(added);
        this.removed = List.copyOf(removed);
    }
}
