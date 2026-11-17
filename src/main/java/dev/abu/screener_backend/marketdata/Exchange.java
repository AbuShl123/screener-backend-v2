package dev.abu.screener_backend.marketdata;

/**
 * A supported exchange. One exchange spans one or more {@link Venue}s.
 *
 * <p>Used as the key of the {@code screener.exchanges.*} configuration map, so the enum
 * constant names must match the YAML keys (relaxed binding: {@code binance} → {@code BINANCE}).
 * The names are also the {@code exchange} identifiers of the WebSocket feed and {@code /api/exchanges}.
 */
public enum Exchange {
    BINANCE("Binance"),
    MEXC("MEXC"),
    BYBIT("Bybit");

    private final String label;

    Exchange(String label) {
        this.label = label;
    }

    /** Human-readable display name, e.g. {@code "Bybit"}. A proper noun, not localized. */
    public String label() {
        return label;
    }
}
