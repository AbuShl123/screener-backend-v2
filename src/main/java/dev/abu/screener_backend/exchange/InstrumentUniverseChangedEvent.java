package dev.abu.screener_backend.exchange;

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
 * not symbols: on the first refresh it is the entire universe, which is what the transport uses to
 * build its connection pools.
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
