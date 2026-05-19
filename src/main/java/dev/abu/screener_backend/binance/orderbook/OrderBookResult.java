package dev.abu.screener_backend.binance.orderbook;

public enum OrderBookResult {
    OK,              // event processed normally (applied, buffered, or silently dropped)
    NEEDS_SNAPSHOT,  // PENDING→QUEUED transition; consumer must call enqueue()
    NEEDS_RESYNC,    // sync failure; consumer must call enqueue()
    DROPPED          // event dropped for a known, non-actionable reason
}
