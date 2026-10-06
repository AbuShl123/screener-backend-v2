package dev.abu.screener_backend.feed.depth;

/**
 * Internal only: coalescing in {@link OrderBookFeedStore} needs ADD vs. UPDATE (ADD then DROP in one
 * tick cancels out). On the wire every message is {@code DEPTH}; DROP is sent as {@code "data":null}.
 */
public enum FeedEventType { ADD, UPDATE, DROP }
