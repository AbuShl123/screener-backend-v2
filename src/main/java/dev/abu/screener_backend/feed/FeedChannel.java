package dev.abu.screener_backend.feed;

import dev.abu.screener_backend.ws.UserWebSocketSession;

import java.util.List;

/**
 * One kind of data delivered over the {@code /ws} feed (depth today; spikes and clusters later).
 * {@link FeedBroadcaster} runs the per-session loop and knows nothing about what a channel sends.
 *
 * <p>All three methods run on the broadcaster's single {@code @Scheduled} thread, so a channel may
 * keep per-tick state in plain fields between {@link #drain()} and the collect calls, and reuse one
 * {@code StringBuilder}.
 */
public interface FeedChannel {

    /**
     * Once per tick, on the broadcaster thread, before any collect call. Drains this channel's
     * stores and builds the tick's shared bodies. Runs even when no session is connected.
     */
    void drain();

    /** Appends this session's live messages for the current tick. */
    void collectUpdates(UserWebSocketSession session, List<String> out);

    /**
     * Appends this session's SNAPSHOT entries. They must reflect the state as of the last
     * {@link #drain()}: anything not yet drained arrives as a live message next tick, so it must not
     * also be in the snapshot unless re-sending it is harmless (a state upsert).
     */
    void collectSnapshot(UserWebSocketSession session, List<String> out);
}
