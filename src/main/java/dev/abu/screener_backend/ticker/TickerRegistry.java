package dev.abu.screener_backend.ticker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe in-memory store for the current set of eligible Binance tickers.
 *
 * <p>Backed by an {@link AtomicReference} holding an immutable {@link Map} snapshot.
 * This design guarantees:
 * <ul>
 *   <li><strong>Lock-free reads</strong> — {@link #getAll()} is a single {@code get()}
 *       with no contention, safe to call from any thread at any time.</li>
 *   <li><strong>Atomic refresh</strong> — readers always see either the complete old map
 *       or the complete new map, never a partially-populated intermediate state.</li>
 * </ul>
 *
 * <p>A {@code ConcurrentHashMap} is intentionally not used here: draining and repopulating
 * it during a refresh would expose a window of partial state to concurrent readers.
 * {@link AtomicReference#set(Object)} eliminates that window entirely with a single
 * pointer swap.
 */
@Slf4j
@Component
public class TickerRegistry {

    private final AtomicReference<Map<String, Ticker>> registry =
            new AtomicReference<>(Collections.emptyMap());

    /**
     * Atomically replaces the entire registry with the given ticker map.
     * All subsequent calls to {@link #getAll()} will return the new snapshot immediately.
     *
     * @param tickers new ticker map keyed by symbol; must not be {@code null}
     */
    public void replace(Map<String, Ticker> tickers) {
        registry.set(Collections.unmodifiableMap(tickers));
        log.info("Ticker registry updated — {} tickers tracked", tickers.size());
    }

    /**
     * Returns the current immutable ticker map keyed by symbol.
     * Never {@code null}; returns an empty map before the first successful fetch.
     *
     * @return current snapshot of all tracked tickers
     */
    public Map<String, Ticker> getAll() {
        return registry.get();
    }

    /**
     * Looks up a ticker by its symbol.
     *
     * @param symbol Binance symbol string, e.g. {@code "BTCUSDT"}
     * @return an {@link Optional} containing the ticker, or empty if not tracked
     */
    public Optional<Ticker> find(String symbol) {
        return Optional.ofNullable(registry.get().get(symbol));
    }

    /**
     * Returns the number of tickers currently tracked.
     *
     * @return total tracked ticker count
     */
    public int size() {
        return registry.get().size();
    }
}
