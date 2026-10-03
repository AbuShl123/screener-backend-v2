package dev.abu.screener_backend.exchange.spi;

import dev.abu.screener_backend.exchange.book.BookSlot;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * How one venue fetches REST snapshots: endpoint, request cost, rate-limit rules and ban handling.
 * Everything else about recovery — accepting requests, batching, the one-outcome-per-request
 * guarantee, publishing into the ring — is core's {@code SnapshotRequestQueue}.
 *
 * <p>Built by the venue's adapter config and handed to {@code SnapshotQueueFactory}. A venue that
 * recovers in-stream (resubscribe) does not implement this; it supplies its own
 * {@link RecoverySink}.
 */
public interface SnapshotFetcher {

    /**
     * Cheap, called from shard consumer threads on every diff of a {@code PENDING} book: volatile
     * reads only, no locks, no allocation. {@code false} while the venue is banned or out of
     * budget — the queue then refuses, and books stay {@code PENDING} without buffering.
     */
    boolean isAcceptingRequests();

    /**
     * Sends snapshot requests for this batch however the venue's rate limits allow. Must report
     * exactly one outcome per slot; a slot it decides not to send (budget) is reported as failed.
     * The returned stage completes once every slot has an outcome. It may spread the batch over
     * time, because the queue accepts nothing until the stage completes.
     *
     * <p>Called from the queue's scheduler thread: start the requests and return, never block.
     * Core fails any slot left unreported when the stage completes or times out, and drops any
     * report after that.
     */
    CompletionStage<Void> fetchAll(List<BookSlot> batch, SnapshotOutcome outcome);
}
