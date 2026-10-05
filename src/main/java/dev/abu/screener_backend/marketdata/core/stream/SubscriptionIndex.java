package dev.abu.screener_backend.marketdata.core.stream;

import dev.abu.screener_backend.marketdata.Instrument;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Per-connection {@code routingKey → instrumentId} lookup, consulted on every inbound frame. The
 * routing key is whatever the venue's {@code StreamProtocol} extracts from a data frame (for
 * Binance, the native symbol).
 *
 * <p>Per-connection because a connection serves exactly one venue, so the routing key alone is
 * unambiguous — no {@code (venue, key)} composite needed on the hot path. Resolving to an
 * {@code int} here is what makes {@code String.intern()} unnecessary in the reader callback.
 *
 * <h3>Why the map, and not the zero-allocation version</h3>
 * The allocation-free form (open-addressed {@code String[]} + {@code int[]}, hashing the char range
 * in place and verifying a probe hit with {@code regionMatches}) is a one-file drop-in later, which
 * is why this class exists as a seam at all. It is deliberately not written yet: it is hand-rolled
 * probing in the hottest method in the application, its failure mode on a bad verify is an event
 * applied to the <em>wrong instrument's book</em>, and the frame {@code String} that java-websocket
 * already allocated (200–2 000 B) dominates the ~40 B substring anyway. Deferred pending a
 * microbenchmark.
 */
public final class SubscriptionIndex {

    private final Map<String, Integer> byRoutingKey;

    /**
     * @throws IllegalArgumentException if two instruments share a routing key — one's frames would
     *         silently be routed to the other's book
     */
    public SubscriptionIndex(List<Instrument> instruments, Function<Instrument, String> routingKey) {
        Map<String, Integer> index = new HashMap<>(instruments.size() * 2);
        for (Instrument instrument : instruments) {
            String key = routingKey.apply(instrument);
            Integer previous = index.put(key, instrument.id());
            if (previous != null) {
                throw new IllegalArgumentException("Routing key '" + key + "' maps to instruments "
                        + previous + " and " + instrument.id() + " on one connection");
            }
        }
        this.byRoutingKey = Map.copyOf(index);
    }

    /**
     * Resolves the routing key occupying {@code [start, end)} of {@code msg} to an instrument id.
     *
     * @return the instrument id, or {@code -1} if this connection never subscribed to that key
     */
    public int resolve(String msg, int start, int end) {
        Integer id = byRoutingKey.get(msg.substring(start, end));
        return id == null ? -1 : id;
    }

    public int size() {
        return byRoutingKey.size();
    }
}
