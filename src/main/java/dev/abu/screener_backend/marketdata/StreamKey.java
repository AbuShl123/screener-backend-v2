package dev.abu.screener_backend.marketdata;

import java.util.Objects;

/**
 * {@code (venue, kind)} — the transport unit. One connection pool serves one stream key, and every
 * connection in it subscribes one channel: kinds are never mixed on one socket.
 *
 * @param venue the venue streamed
 * @param kind  the channel subscribed on that venue
 */
public record StreamKey(Venue venue, StreamKind kind) {

    private static final Venue[] VENUES = Venue.values();
    private static final StreamKind[] KINDS = StreamKind.values();

    /** Number of distinct keys; {@link #index()} is in {@code [0, COUNT)}. */
    public static final int COUNT = VENUES.length * KINDS.length;

    private static final String[] LOG_NAMES = new String[COUNT];

    static {
        for (Venue v : VENUES) {
            for (StreamKind k : KINDS) {
                LOG_NAMES[v.ordinal() * KINDS.length + k.ordinal()] = v.name() + "/" + k.name();
            }
        }
    }

    public StreamKey {
        Objects.requireNonNull(venue, "venue");
        Objects.requireNonNull(kind, "kind");
    }

    /** Dense index over every key, for per-stream arrays such as frame counters. */
    public int index() {
        return venue.ordinal() * KINDS.length + kind.ordinal();
    }

    /** {@code BINANCE_FUTURES/DEPTH}, precomputed: for log lines and thread names. */
    public String logName() {
        return LOG_NAMES[index()];
    }

    @Override
    public String toString() {
        return logName();
    }
}
