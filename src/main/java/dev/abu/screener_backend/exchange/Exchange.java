package dev.abu.screener_backend.exchange;

/**
 * A supported exchange. One exchange spans one or more {@link Venue}s.
 *
 * <p>Used as the key of the {@code screener.exchanges.*} configuration map, so the enum
 * constant names must match the YAML keys (relaxed binding: {@code binance} → {@code BINANCE}).
 */
public enum Exchange {
    BINANCE
}
