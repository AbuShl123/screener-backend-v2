package dev.abu.screener_backend.exchange.book;

public enum OrderBookState {
    PENDING,             // needs snapshot but haven't queued yet, diffs dropped
    SNAPSHOT_REQUESTED,  // queued for snapshot, diffs are buffered
    SYNCED,              // live; diffs parsed and applied immediately
}