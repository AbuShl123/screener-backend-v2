package dev.abu.screener_backend.marketdata.core.book;

public enum OrderBookState {
    PENDING,             // orderbook is desynced - needs recovery
    RECOVERING,          // orderbook is recovering (not synced yet)
    SYNCED,              // live - all synced and healthy
}