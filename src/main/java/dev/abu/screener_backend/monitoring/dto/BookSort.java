package dev.abu.screener_backend.monitoring.dto;

/** {@code sort} query parameter for {@code GET /api/monitoring/orderbook/books}. */
public enum BookSort {
    SIZE,
    UPDATE_RATE,
    IDLE,
    RESYNCS,
    SYMBOL
}
