package dev.abu.screener_backend.exchange;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assigns and resolves dense {@code int} instrument ids.
 *
 * <h3>Id rules — all load-bearing</h3>
 * <ul>
 *   <li><b>Dense</b> — ids come from a counter; the space is {@code [0, everRegistered)} for the
 *       life of the process. That is what lets the order-book store be a plain array.</li>
 *   <li><b>Stable</b> — re-registering the same {@code (venue, nativeSymbol)} returns the same id.
 *       The 4-hourly universe refresh must never reshuffle ids.</li>
 *   <li><b>Never transferred</b> — a delisted instrument's id is retired and left as a hole. It is
 *       never handed to a different instrument: a stale in-flight event applied to the wrong book
 *       is silent corruption, and no memory saving is worth that.</li>
 *   <li><b>Never persisted</b> — nothing writes an id to the database or to an API payload.
 *       {@code (venue, nativeSymbol)} is the durable identity.</li>
 * </ul>
 *
 * <h3>Threading</h3>
 * Registration happens on the discovery thread only. Lookups come from request threads
 * (rule validation, monitoring) and are served by a {@link ConcurrentHashMap} plus a
 * {@code volatile} id array republished on every growth.
 *
 * <p>All methods here are cold-path. Nothing on the hot path resolves an id back to an
 * {@link Instrument} — the consumer reads {@code slots[id]} directly.
 */
@Slf4j
@Component
public final class InstrumentRegistry {

    private static final Instrument[] EMPTY = new Instrument[0];

    private final ConcurrentHashMap<String, Instrument> byKey = new ConcurrentHashMap<>();

    /** Every {@link Instrument#ruleKey} ever registered, on any exchange. */
    private final Set<String> ruleKeys = ConcurrentHashMap.newKeySet();

    /** Copy-on-write; index is the instrument id. Holes are {@code null}. */
    private volatile Instrument[] byId = EMPTY;

    /** Discovery thread only. */
    private int nextId = 0;

    /**
     * Registers an instrument, or returns the existing one if {@code (venue, nativeSymbol)} was
     * already registered. Idempotent; call only from the discovery thread.
     *
     * <p>An existing instrument is returned unchanged, including its {@code quantityMultiplier}. A
     * contract size changed by the exchange is therefore not picked up until restart — rare enough
     * to accept, but logged so it is not silent.
     */
    public synchronized Instrument register(Venue venue, String nativeSymbol, String base, String quote,
                                            double quantityMultiplier) {
        String key = key(venue, nativeSymbol);
        Instrument existing = byKey.get(key);
        if (existing != null) {
            if (existing.quantityMultiplier() != quantityMultiplier) {
                log.warn("[{}] quantity multiplier changed {} -> {}; keeping {} until restart",
                        existing.logName(), existing.quantityMultiplier(), quantityMultiplier,
                        existing.quantityMultiplier());
            }
            return existing;
        }

        Instrument instrument = Instrument.of(nextId++, venue, nativeSymbol, base, quote, quantityMultiplier);

        Instrument[] grown = new Instrument[Math.max(instrument.id() + 1, byId.length)];
        System.arraycopy(byId, 0, grown, 0, byId.length);
        grown[instrument.id()] = instrument;

        byKey.put(key, instrument);
        ruleKeys.add(instrument.ruleKey());
        byId = grown; // volatile store publishes both the array and the map entry above it
        return instrument;
    }

    /** Looks up an instrument by its durable identity. Cold path. */
    public Optional<Instrument> find(Venue venue, String nativeSymbol) {
        return Optional.ofNullable(byKey.get(key(venue, nativeSymbol)));
    }

    /**
     * Whether {@code symbol} is tracked on {@code market} on <b>any</b> exchange — the question
     * rule validation asks, since a user rule applies on every exchange. Matches by
     * {@link Instrument#ruleKey}, so {@code symbol} is the normalized {@code BASEQUOTE} spelling,
     * not a native one. Cold path.
     */
    public boolean isTracked(String symbol, Market market) {
        return ruleKeys.contains(Instrument.ruleKey(symbol, market));
    }

    /** Resolves an id, or {@code null} if the id was never assigned. Cold path. */
    public Instrument byId(int id) {
        Instrument[] snapshot = byId;
        return (id >= 0 && id < snapshot.length) ? snapshot[id] : null;
    }

    /**
     * Human-readable name for an id — logging, warnings and monitoring only.
     *
     * <p>This method is why identity can be an {@code int} without every sync log line degrading
     * to {@code [4127]}, and therefore why {@code String symbol} does not need to creep back onto
     * {@code OrderBook} "just for logging".
     */
    public String describe(int id) {
        Instrument instrument = byId(id);
        return instrument == null ? "unknown#" + id : instrument.logName();
    }

    /**
     * Number of ids ever handed out. The slot table is sized from this, so it counts retired
     * instruments too.
     */
    public int everRegistered() {
        return byId.length;
    }

    /** All live instruments, in unspecified order. Cold path — {@code /api/tickers}. */
    public Collection<Instrument> all() {
        return List.copyOf(byKey.values());
    }

    private static String key(Venue venue, String nativeSymbol) {
        return venue.name() + '|' + nativeSymbol;
    }
}
